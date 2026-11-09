package com.medimate.data.medication;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/** 기기에 쌓인 변경을 서버로 보내고 서버 변경을 받는다. 네트워크가 연결되면 앱이 꺼져 있어도 실행된다. */
public class MedicationSyncWorker extends Worker {

    private static final String WORK_NAME = "medication_sync";

    public MedicationSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    static void enqueue(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(MedicationSyncWorker.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        // 실행 중이어도 새로 시작해 방금 생긴 변경까지 보낸다. 문서 id가 정해져 있어 다시 보내도 중복되지 않는다
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request);
    }

    static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }

    @NonNull
    @Override
    public Result doWork() {
        return MedicationRepository.get(getApplicationContext()).syncBlocking() ? Result.success() : Result.retry();
    }
}
