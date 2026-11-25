package com.medimate.pill;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.medimate.net.ApiClient;
import com.medimate.net.ApiError;
import com.medimate.net.ApiException;
import com.medimate.net.HandlerScheduler;
import com.medimate.net.JobPoller;
import com.medimate.pill.model.JobAccepted;
import com.medimate.pill.model.PillHints;
import com.medimate.pill.model.PillIdentificationJob;
import com.medimate.pill.model.SelectResult;
import com.medimate.plan.model.CurrentMedication;

/** 업로드 → 식별 요청 → 폴링 → 선택. 화면 회전에도 진행 상태가 유지된다. */
public class PillResultViewModel extends ViewModel {

    public enum Phase { UPLOADING, IDENTIFYING, RESULT, SELECTING, SELECTED, ERROR }

    /** 화면에 그릴 상태 한 벌 (불변). */
    public static final class State {
        public final Phase phase;
        public final String message;
        public final PillIdentificationJob job;
        public final SelectResult selected;
        public final ApiException error;

        State(Phase phase, String message, PillIdentificationJob job, SelectResult selected, ApiException error) {
            this.phase = phase;
            this.message = message;
            this.job = job;
            this.selected = selected;
            this.error = error;
        }
    }

    /** 서버가 Claude 관찰·재순위(각 최대 40초)까지 거치면 오래 걸릴 수 있다. */
    private static final long POLL_TIMEOUT_MS = 120_000;

    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final HandlerScheduler scheduler = new HandlerScheduler();
    private final PillRepository repository;
    private final JobPoller<PillIdentificationJob> poller;
    private boolean started;
    /** "맞아요, 내 약에 추가"를 눌렀는지. 화면이 한 번 꺼내 쓴다. */
    private boolean pendingAdd;
    /** 같은 결과를 화면 회전 때마다 다시 읽지 않도록 마지막으로 읽은 문구를 기억한다. */
    public String lastSpoken;

    public PillResultViewModel() {
        this(new PillRepository(ApiClient.get()));
    }

    PillResultViewModel(PillRepository repository) {
        this.repository = repository;
        this.poller = new JobPoller<>(repository::getJob, PillRepository::isFinished, io, scheduler, POLL_TIMEOUT_MS);
    }

    /** 내 약에 추가하기로 했으면 true를 한 번만 돌려준다 (화면 회전 때 두 번 저장하지 않도록). */
    public boolean consumeAddRequest() {
        boolean add = pendingAdd;
        pendingAdd = false;
        return add;
    }

    public LiveData<State> state() {
        return state;
    }

    /** 최초 1번만 실행된다 (화면 회전으로 Activity가 다시 만들어져도 중복 요청하지 않음). */
    /** currentMedications: 기기에 저장된 복용 중인 약. 그 약인 후보에 "내 약" 표시가 붙는다. */
    public void start(File front, File back, PillHints hints, List<CurrentMedication> currentMedications) {
        if (started) {
            return;
        }
        started = true;
        state.setValue(new State(Phase.UPLOADING, "사진을 올리고 있습니다.", null, null, null));
        io.execute(() -> {
            try {
                byte[] frontJpeg = Files.readAllBytes(front.toPath());
                byte[] backJpeg = back == null ? null : Files.readAllBytes(back.toPath());
                JobAccepted accepted = repository.requestIdentification(frontJpeg, backJpeg, hints, currentMedications);
                state.postValue(new State(Phase.IDENTIFYING, accepted.ttsMessage, null, null, null));
                scheduler.schedule(() -> poll(accepted), 0);
            } catch (ApiException e) {
                state.postValue(new State(Phase.ERROR, e.error().ttsMessage, null, null, e));
            } catch (IOException e) {
                ApiException error = new ApiException(ApiException.NO_RESPONSE, ApiError.of(
                        ApiException.CODE_UNEXPECTED, "사진 파일을 읽을 수 없습니다.",
                        "사진을 읽을 수 없습니다. 다시 찍어 주세요.", false));
                state.postValue(new State(Phase.ERROR, error.error().ttsMessage, null, null, error));
            }
        });
    }

    private void poll(JobAccepted accepted) {
        poller.start(accepted.jobId, accepted.pollAfterMs, new JobPoller.Listener<PillIdentificationJob>() {
            @Override
            public void onProgress(PillIdentificationJob job) {
                // 처리 중 상태는 화면 문구를 바꾸지 않는다 (같은 안내를 반복해서 읽지 않도록)
            }

            @Override
            public void onDone(PillIdentificationJob job) {
                state.setValue(new State(Phase.RESULT, job.ttsScript, job, null, null));
            }

            @Override
            public void onError(ApiException error) {
                state.setValue(new State(Phase.ERROR, error.error().ttsMessage, null, null, error));
            }

            @Override
            public void onTimeout() {
                ApiException error = new ApiException(ApiException.NO_RESPONSE, ApiError.of(
                        "APP-PID-TIMEOUT", "식별이 너무 오래 걸립니다.",
                        "알약 확인이 오래 걸리고 있습니다. 잠시 후 다시 시도해 주세요.", true));
                state.setValue(new State(Phase.ERROR, error.error().ttsMessage, null, null, error));
            }
        });
    }

    /** 서버는 일정을 저장하지 않는다. addToMedications면 화면이 SELECTED 때 draftPlan을 기기에 저장한다. */
    public void select(String itemSeq, boolean addToMedications) {
        pendingAdd = addToMedications;
        State current = state.getValue();
        if (current == null || current.job == null) {
            return;
        }
        PillIdentificationJob job = current.job;
        state.setValue(new State(Phase.SELECTING, "선택한 약을 저장하고 있습니다.", job, null, null));
        io.execute(() -> {
            try {
                SelectResult result = repository.select(job.jobId, itemSeq);
                state.postValue(new State(Phase.SELECTED, null, job, result, null));
            } catch (ApiException e) {
                // 선택에 실패해도 후보 목록은 그대로 보여 준다
                state.postValue(new State(Phase.RESULT, e.error().ttsMessage, job, null, e));
            }
        });
    }

    @Override
    protected void onCleared() {
        poller.cancel();
        scheduler.cancelAll();
        io.shutdownNow();
    }
}
