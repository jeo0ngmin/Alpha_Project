package com.medimate;

import android.app.Application;

import com.kakao.sdk.common.KakaoSdk;
import com.medimate.data.medication.MedicationRepository;
import com.medimate.reminder.ReminderNotifier;

public class MedimateApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 네이티브 앱 키는 secrets.properties → BuildConfig로만 읽는다 (README 7-5)
        KakaoSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY);
        ReminderNotifier.createChannel(this);
        // 로그아웃·계정 전환 때 이전 사용자의 알림과 기기 데이터를 지운다 (README 9-6)
        MedicationRepository.get(this).watchAccount();
    }
}
