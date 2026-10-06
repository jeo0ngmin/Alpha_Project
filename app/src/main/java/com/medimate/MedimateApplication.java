package com.medimate;

import android.app.Application;

import com.kakao.sdk.common.KakaoSdk;

public class MedimateApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 네이티브 앱 키는 secrets.properties → BuildConfig로만 읽는다 (README 7-5)
        KakaoSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY);
    }
}
