package com.medimate.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.Test;

import com.medimate.pill.PillRepository;
import com.medimate.pill.model.PillIdentificationJob;
import com.medimate.pill.model.PillJobStatus;

public class JobPollerTest {

    /** 대기 없이 순서대로 실행하되, 요청된 대기 시간은 기록한다. */
    private static final class FakeScheduler implements JobPoller.Scheduler {
        final Deque<Runnable> queue = new ArrayDeque<>();
        final List<Long> delays = new ArrayList<>();

        @Override
        public void schedule(Runnable task, long delayMs) {
            delays.add(delayMs);
            queue.add(task);
        }

        void drain() {
            while (!queue.isEmpty()) {
                queue.poll().run();
            }
        }
    }

    private static final class RecordingListener implements JobPoller.Listener<PillIdentificationJob> {
        int progress;
        PillIdentificationJob done;
        ApiException error;
        boolean timedOut;

        @Override public void onProgress(PillIdentificationJob job) { progress++; }
        @Override public void onDone(PillIdentificationJob job) { done = job; }
        @Override public void onError(ApiException e) { error = e; }
        @Override public void onTimeout() { timedOut = true; }
    }

    /** 미리 정한 순서대로 잡 상태를 돌려주거나 예외를 던진다. */
    private static JobPoller.Fetcher<PillIdentificationJob> sequence(Object... results) {
        Deque<Object> q = new ArrayDeque<>(List.of(results));
        return jobId -> {
            Object next = q.size() > 1 ? q.poll() : q.peek();
            if (next instanceof ApiException) {
                throw (ApiException) next;
            }
            return job((PillJobStatus) next);
        };
    }

    private static PillIdentificationJob job(PillJobStatus status) {
        PillIdentificationJob j = new PillIdentificationJob();
        j.jobId = "pid_x";
        j.status = status;
        return j;
    }

    private static ApiException networkError() {
        return new ApiException(ApiException.NO_RESPONSE, ApiError.of("APP-NET-0000", "끊김", "끊김", true));
    }

    private static ApiException gone() {
        return new ApiException(410, ApiError.of("MM-PID-4100", "만료", "만료", false));
    }

    private final FakeScheduler scheduler = new FakeScheduler();
    private final RecordingListener listener = new RecordingListener();

    private void run(JobPoller.Fetcher<PillIdentificationJob> fetcher, long pollAfterMs, long timeoutMs) {
        poller(fetcher, timeoutMs).start("pid_x", pollAfterMs, listener);
        scheduler.drain();
    }

    private JobPoller<PillIdentificationJob> poller(JobPoller.Fetcher<PillIdentificationJob> fetcher, long timeoutMs) {
        return new JobPoller<>(fetcher, PillRepository::isFinished, Runnable::run, scheduler, timeoutMs);
    }

    @Test
    public void pollsUntilTerminalStatus() {
        run(sequence(PillJobStatus.PROCESSING, PillJobStatus.PROCESSING, PillJobStatus.COMPLETED), 1500, 60_000);
        assertNotNull(listener.done);
        assertEquals(PillJobStatus.COMPLETED, listener.done.status);
        assertEquals(2, listener.progress);
        assertTrue(scheduler.delays.contains(1500L));
    }

    @Test
    public void delayIsClampedToSaneRange() {
        run(sequence(PillJobStatus.COMPLETED), 10, 60_000);
        assertEquals(Long.valueOf(JobPoller.MIN_DELAY_MS), scheduler.delays.get(0));
    }

    @Test
    public void timesOutWhenJobNeverFinishes() {
        run(sequence(PillJobStatus.PROCESSING), 1000, 3000);
        assertTrue(listener.timedOut);
        assertEquals(2, listener.progress); // 3번째 조회에서 시간 초과
    }

    @Test
    public void transientNetworkErrorsAreRetried() {
        run(sequence(networkError(), networkError(), PillJobStatus.COMPLETED), 1000, 60_000);
        assertNotNull(listener.done);
    }

    @Test
    public void tooManyNetworkErrorsGiveUp() {
        run(sequence(networkError(), networkError(), networkError(), PillJobStatus.COMPLETED), 1000, 60_000);
        assertNotNull(listener.error);
        assertEquals(null, listener.done);
    }

    @Test
    public void nonRetryableErrorStopsImmediately() {
        run(sequence(gone()), 1000, 60_000);
        assertEquals(410, listener.error.httpStatus());
    }

    @Test
    public void cancelStopsCallbacks() {
        JobPoller<PillIdentificationJob> poller = poller(sequence(PillJobStatus.COMPLETED), 60_000);
        poller.start("pid_x", 1000, listener);
        poller.cancel();
        scheduler.drain();
        assertEquals(null, listener.done);
    }
}
