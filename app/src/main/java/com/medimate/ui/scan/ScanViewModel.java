package com.medimate.ui.scan;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.medimate.bag.BagRepository;
import com.medimate.bag.model.BagJob;
import com.medimate.bag.model.BagJobStatus;
import com.medimate.model.Drug;
import com.medimate.net.ApiClient;
import com.medimate.net.ApiException;
import com.medimate.net.HandlerScheduler;
import com.medimate.net.JobPoller;
import com.medimate.pill.model.JobAccepted;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 사진 → MediMate 서버 약봉투 인식(CLOVA OCR → 개인정보 가림 → 정리 → 식약처 대조) → 약 목록.
 * 예전 흐름(ML Kit → GPT → e약은요)은 서버가 같은 일을 하므로 쓰지 않는다.
 * 후보 선택·확정(일정 초안)은 다음 단계.
 */
public class ScanViewModel extends ViewModel {

    static final String MSG_READING = "약봉투를 읽고 있어요";
    static final String MSG_SEARCHING = "약 정보를 찾고 있어요";
    static final String MSG_NO_DRUG = "약 이름을 찾지 못했어요. 약봉투를 다시 찍어 주세요.";
    static final String MSG_FAILED = "약봉투를 읽지 못했어요. 다시 찍어 주세요.";
    static final String MSG_NETWORK = "서버에 연결하지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요.";
    static final String MSG_TIMEOUT = "읽는 데 시간이 너무 오래 걸려요. 잠시 뒤 다시 시도해 주세요.";

    /** 서버 인식(CLOVA + Claude)이 보통 10~30초 걸린다 */
    private static final long POLL_TIMEOUT_MS = 90_000;

    private final BagRepository repository = new BagRepository(ApiClient.get());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final HandlerScheduler scheduler = new HandlerScheduler();
    private JobPoller<BagJob> poller;

    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String> status = new MutableLiveData<>();
    private final MutableLiveData<List<Drug>> drugs = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();

    public LiveData<Boolean> getIsLoading() {
        return isLoading;
    }

    /** 진행 안내 ("약봉투를 읽고 있어요" → "약 정보를 찾고 있어요"). 화면에 보여 주고 읽어 준다 */
    public LiveData<String> getStatus() {
        return status;
    }

    /** 완성된 약 목록. 화면이 받아 가면 consumeDrugs()로 비운다 */
    public LiveData<List<Drug>> getDrugs() {
        return drugs;
    }

    /** 사용자에게 그대로 읽어 줄 실패 안내 */
    public LiveData<String> getError() {
        return error;
    }

    /** jpeg: ImageCompressor로 줄인 사진 */
    public void start(byte[] jpeg) {
        isLoading.setValue(true);
        status.setValue(MSG_READING);
        io.execute(() -> {
            try {
                JobAccepted accepted = repository.requestRecognition(jpeg);
                scheduler.schedule(() -> poll(accepted), 0);
            } catch (ApiException e) {
                scheduler.schedule(() -> fail(messageOf(e)), 0);
            }
        });
    }

    public void consumeDrugs() {
        drugs.setValue(null);
    }

    private void poll(JobAccepted accepted) {
        status.setValue(MSG_SEARCHING);
        poller = new JobPoller<>(repository::getJob, BagRepository::isFinished, io, scheduler, POLL_TIMEOUT_MS);
        poller.start(accepted.jobId, accepted.pollAfterMs, new JobPoller.Listener<BagJob>() {
            @Override
            public void onProgress(BagJob job) {
            }

            @Override
            public void onDone(BagJob job) {
                if (job.status == BagJobStatus.FAILED) {
                    fail(job.error != null && job.error.ttsMessage != null ? job.error.ttsMessage : MSG_FAILED);
                    return;
                }
                List<Drug> result = BagDrugMapper.toDrugs(job);
                if (result.isEmpty()) {
                    fail(MSG_NO_DRUG);
                    return;
                }
                isLoading.setValue(false);
                drugs.setValue(result);
            }

            @Override
            public void onError(ApiException e) {
                fail(messageOf(e));
            }

            @Override
            public void onTimeout() {
                fail(MSG_TIMEOUT);
            }
        });
    }

    private static String messageOf(ApiException e) {
        if (e.error() != null && e.error().ttsMessage != null) {
            return e.error().ttsMessage;
        }
        return e.httpStatus() == ApiException.NO_RESPONSE ? MSG_NETWORK : MSG_FAILED;
    }

    private void fail(String message) {
        isLoading.setValue(false);
        error.setValue(message);
    }

    @Override
    protected void onCleared() {
        if (poller != null) {
            poller.cancel();
        }
        scheduler.cancelAll();
        io.shutdownNow();
    }
}
