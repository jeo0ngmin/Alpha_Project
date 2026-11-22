package com.medimate.net;

import java.util.concurrent.Executor;

/**
 * 서버의 비동기 작업(알약 식별, 약봉투 인식)이 끝날 때까지 결과 조회를 반복한다.
 * 조회는 io 실행기에서, 콜백과 대기는 scheduler(보통 메인 스레드)에서 실행된다.
 *
 * <pre>
 * 알약:   new JobPoller&lt;&gt;(pillRepo::getJob, job -&gt; job.status != null &amp;&amp; job.status.isTerminal(), ...)
 * 약봉투: new JobPoller&lt;&gt;(bagRepo::getJob, BagJob::isFinished, ...)
 * </pre>
 *
 * @param <J> 조회 결과(잡) 타입
 */
public final class JobPoller<J> {

    public interface Fetcher<J> {
        J fetch(String jobId) throws ApiException;
    }

    /** 더 조회할 필요가 없는 상태인지. */
    public interface DoneCheck<J> {
        boolean isDone(J job);
    }

    public interface Scheduler {
        void schedule(Runnable task, long delayMs);
    }

    public interface Listener<J> {
        /** 아직 처리 중일 때마다 호출된다. */
        void onProgress(J job);

        void onDone(J job);

        void onError(ApiException error);

        void onTimeout();
    }

    static final long MIN_DELAY_MS = 500;
    static final long MAX_DELAY_MS = 5000;
    /** 연속으로 이만큼 재시도 가능한 오류(네트워크 끊김 등)가 나면 포기한다. */
    static final int MAX_CONSECUTIVE_ERRORS = 3;

    private final Fetcher<J> fetcher;
    private final DoneCheck<J> doneCheck;
    private final Executor io;
    private final Scheduler scheduler;
    private final long timeoutMs;
    private volatile boolean cancelled;

    public JobPoller(Fetcher<J> fetcher, DoneCheck<J> doneCheck, Executor io, Scheduler scheduler, long timeoutMs) {
        this.fetcher = fetcher;
        this.doneCheck = doneCheck;
        this.io = io;
        this.scheduler = scheduler;
        this.timeoutMs = timeoutMs;
    }

    /** pollAfterMs는 서버가 JobAccepted로 알려 준 간격. */
    public void start(String jobId, long pollAfterMs, Listener<J> listener) {
        long delay = Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, pollAfterMs));
        int maxAttempts = (int) Math.max(1, timeoutMs / delay);
        scheduler.schedule(() -> poll(jobId, delay, 1, maxAttempts, 0, listener), delay);
    }

    public void cancel() {
        cancelled = true;
    }

    private void poll(String jobId, long delay, int attempt, int maxAttempts, int errors, Listener<J> listener) {
        if (cancelled) {
            return;
        }
        io.execute(() -> {
            try {
                J job = fetcher.fetch(jobId);
                scheduler.schedule(() -> handle(jobId, job, delay, attempt, maxAttempts, listener), 0);
            } catch (ApiException e) {
                scheduler.schedule(() -> handleError(jobId, e, delay, attempt, maxAttempts, errors, listener), 0);
            }
        });
    }

    private void handle(String jobId, J job, long delay, int attempt, int maxAttempts, Listener<J> listener) {
        if (cancelled) {
            return;
        }
        if (doneCheck.isDone(job)) {
            listener.onDone(job);
        } else if (attempt >= maxAttempts) {
            listener.onTimeout();
        } else {
            listener.onProgress(job);
            scheduler.schedule(() -> poll(jobId, delay, attempt + 1, maxAttempts, 0, listener), delay);
        }
    }

    private void handleError(String jobId, ApiException e, long delay, int attempt, int maxAttempts, int errors,
                             Listener<J> listener) {
        if (cancelled) {
            return;
        }
        if (e.isRetryable() && errors + 1 < MAX_CONSECUTIVE_ERRORS && attempt < maxAttempts) {
            scheduler.schedule(() -> poll(jobId, delay, attempt + 1, maxAttempts, errors + 1, listener), delay);
        } else {
            listener.onError(e);
        }
    }
}
