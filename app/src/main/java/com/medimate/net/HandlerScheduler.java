package com.medimate.net;

import android.os.Handler;
import android.os.Looper;

/** JobPoller.Scheduler의 메인 스레드 구현. 화면(ViewModel)에서 JobPoller를 쓸 때 넘긴다. */
public final class HandlerScheduler implements JobPoller.Scheduler {

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void schedule(Runnable task, long delayMs) {
        handler.postDelayed(task, delayMs);
    }

    /** 화면이 닫힐 때(ViewModel.onCleared) 남은 예약을 지운다. */
    public void cancelAll() {
        handler.removeCallbacksAndMessages(null);
    }
}
