package com.medimate.ui.pill;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.medimate.drug.model.MedicationInfo;
import com.medimate.model.Drug;
import com.medimate.net.ApiClient;
import com.medimate.net.ApiException;
import com.medimate.net.HandlerScheduler;
import com.medimate.net.JobPoller;
import com.medimate.pill.PillRepository;
import com.medimate.pill.model.JobAccepted;
import com.medimate.pill.model.PillCandidate;
import com.medimate.pill.model.PillIdentificationJob;
import com.medimate.pill.model.PillJobStatus;
import com.medimate.ui.drug.MedicationDrugMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 알약 사진 → MediMate 서버 알약 식별(모양·색·각인 + 학습 모델) → 후보 약 정보(e약은요).
 * 서버가 확신하면 1개, 아니면 비슷한 후보 몇 개를 돌려준다. 고르기(select)는 하지 않는다.
 */
public class PillViewModel extends ViewModel {

    static final String MSG_UPLOADING = "알약 사진을 보내고 있어요";
    static final String MSG_ANALYZING = "알약 모양과 글자를 살펴보고 있어요";
    static final String MSG_LOADING_INFO = "약 정보를 찾고 있어요";
    static final String MSG_NOT_FOUND = "어떤 약인지 찾지 못했어요. 알약 앞면이 잘 보이게 다시 찍어 주세요.";
    static final String MSG_FAILED = "알약을 분석하지 못했어요. 다시 찍어 주세요.";
    static final String MSG_NETWORK = "서버에 연결하지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요.";
    static final String MSG_TIMEOUT = "분석하는 데 시간이 너무 오래 걸려요. 잠시 뒤 다시 시도해 주세요.";

    /** 서버가 확신하지 못할 때 보여 줄 후보 수 */
    static final int MAX_CANDIDATES = 3;
    private static final long POLL_TIMEOUT_MS = 90_000;

    /** 분석 결과. drugs가 1개면 확신, 여러 개면 비슷한 후보 */
    public static final class Result {
        public final List<Drug> drugs;
        /** 후보별 한 줄 안내 (업체명 등). drugs와 같은 순서 */
        public final ArrayList<String> notes;
        /** 서버가 만든 안내 문장. 없으면 null */
        public final String spoken;

        Result(List<Drug> drugs, ArrayList<String> notes, String spoken) {
            this.drugs = drugs;
            this.notes = notes;
            this.spoken = spoken;
        }
    }

    private final PillRepository repository = new PillRepository(ApiClient.get());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final HandlerScheduler scheduler = new HandlerScheduler();
    private JobPoller<PillIdentificationJob> poller;
    private boolean started;

    private final MutableLiveData<String> status = new MutableLiveData<>();
    private final MutableLiveData<Result> result = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();

    /** 진행 안내. 화면에 보여 주고 읽어 준다 */
    public LiveData<String> getStatus() {
        return status;
    }

    public LiveData<Result> getResult() {
        return result;
    }

    /** 사용자에게 그대로 읽어 줄 실패 안내 */
    public LiveData<String> getError() {
        return error;
    }

    /** 화면 회전으로 다시 불려도 한 번만 시작한다 */
    public boolean isStarted() {
        return started;
    }

    /** jpeg: ImageCompressor로 줄인 앞면 사진 */
    public void start(byte[] jpeg) {
        started = true;
        status.setValue(MSG_UPLOADING);
        io.execute(() -> {
            try {
                JobAccepted accepted = repository.requestIdentification(jpeg, null, null);
                scheduler.schedule(() -> poll(accepted), 0);
            } catch (ApiException e) {
                scheduler.schedule(() -> fail(messageOf(e)), 0);
            }
        });
    }

    public void failLoad(String message) {
        started = true;
        fail(message);
    }

    private void poll(JobAccepted accepted) {
        status.setValue(MSG_ANALYZING);
        poller = new JobPoller<>(repository::getJob, PillRepository::isFinished, io, scheduler, POLL_TIMEOUT_MS);
        poller.start(accepted.jobId, accepted.pollAfterMs, new JobPoller.Listener<PillIdentificationJob>() {
            @Override
            public void onProgress(PillIdentificationJob job) {
            }

            @Override
            public void onDone(PillIdentificationJob job) {
                onFinished(job);
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

    private void onFinished(PillIdentificationJob job) {
        if (job.status == PillJobStatus.FAILED) {
            fail(orDefault(job.ttsScript, MSG_FAILED));
            return;
        }
        List<PillCandidate> candidates = pick(job);
        if (job.status == PillJobStatus.NOT_FOUND || candidates.isEmpty()) {
            fail(orDefault(job.ttsScript, MSG_NOT_FOUND));
            return;
        }
        status.setValue(MSG_LOADING_INFO);
        io.execute(() -> {
            List<Drug> drugs = new ArrayList<>();
            ArrayList<String> notes = new ArrayList<>();
            for (PillCandidate c : candidates) {
                drugs.add(loadDrug(c));
                notes.add(c.entpName == null ? "" : c.entpName);
            }
            Result done = new Result(drugs, notes, job.ttsScript);
            scheduler.schedule(() -> result.setValue(done), 0);
        });
    }

    /** 확신하면 1위만, 아니면 위에서부터 MAX_CANDIDATES개 */
    static List<PillCandidate> pick(PillIdentificationJob job) {
        List<PillCandidate> all = job.candidatesOrEmpty();
        List<PillCandidate> out = new ArrayList<>();
        if (!job.requiresUserSelection && job.topMatch != null && job.topMatch.itemSeq != null) {
            out.add(job.topMatch);
            return out;
        }
        for (PillCandidate c : all) {
            if (c.itemSeq != null && out.size() < MAX_CANDIDATES) {
                out.add(c);
            }
        }
        if (out.isEmpty() && job.topMatch != null && job.topMatch.itemSeq != null) {
            out.add(job.topMatch);
        }
        return out;
    }

    /** 백그라운드 스레드. 약 정보를 못 받아도 이름은 보여 준다 */
    private Drug loadDrug(PillCandidate c) {
        try {
            MedicationInfo info = repository.getMedication(c.itemSeq);
            return MedicationDrugMapper.toDrug(info, null, null);
        } catch (ApiException e) {
            return MedicationDrugMapper.withoutInfo(c.itemName, c.itemSeq, "", "");
        }
    }

    private static String messageOf(ApiException e) {
        if (e.error() != null && e.error().ttsMessage != null) {
            return e.error().ttsMessage;
        }
        return e.httpStatus() == ApiException.NO_RESPONSE ? MSG_NETWORK : MSG_FAILED;
    }

    private static String orDefault(String s, String fallback) {
        return s == null || s.trim().isEmpty() ? fallback : s;
    }

    private void fail(String message) {
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
