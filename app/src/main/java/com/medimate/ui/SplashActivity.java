package com.medimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.medimate.HomeActivity;
import com.medimate.R;
import com.medimate.WelcomeActivity;
import com.medimate.auth.AuthRepository;
import com.medimate.data.medication.MedicationRepository;

/**
 * 앱 시작 로딩 화면.
 *
 * 로그인 상태를 확인한 뒤
 * 로그인된 사용자는 HomeActivity,
 * 로그인 전 사용자는 WelcomeActivity로 이동한다.
 */
public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DELAY_MS = 800;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final Runnable moveNext =
            this::openNextScreen;


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        // =========================
        // 시작 로딩 화면
        // =========================

        setContentView(
                R.layout.activity_splash
        );


        // 시스템 바 영역 처리 + 밝은 배경 상태바 아이콘
        EdgeToEdgeHelper.padForSystemBars(
                findViewById(
                        R.id.splash_root
                )
        );


        // =========================
        // 기존 로그인 상태 초기화
        // =========================

        AuthRepository auth =
                new AuthRepository();


        if (auth.currentUser() != null) {

            /*
             * 기존 사용자도 프로필이 생성되도록 한다.
             * 완료될 때까지 기다리지는 않는다.
             */
            ProfileSync.ensure(
                    auth.currentUser(),
                    () -> {
                    }
            );


            /*
             * 복약 데이터 및 알림 초기화
             */
            MedicationRepository
                    .get(this)
                    .onAppStarted();
        }


        // =========================
        // 로딩 화면 잠깐 표시
        // =========================

        handler.postDelayed(
                moveNext,
                SPLASH_DELAY_MS
        );
    }


    /**
     * 로그인 여부에 따라 다음 화면으로 이동
     */
    private void openNextScreen() {

        AuthRepository auth =
                new AuthRepository();


        Class<?> nextScreen =
                auth.isSignedIn()
                        ? HomeActivity.class
                        : WelcomeActivity.class;


        Intent intent =
                new Intent(
                        SplashActivity.this,
                        nextScreen
                );


        startActivity(
                intent
        );


        /*
         * 뒤로가기를 눌러 Splash 화면으로
         * 돌아오지 않도록 종료한다.
         */
        finish();
    }


    @Override
    protected void onDestroy() {

        /*
         * 화면이 먼저 닫힌 경우
         * 예약된 화면 이동 작업 제거
         */
        handler.removeCallbacks(
                moveNext
        );

        super.onDestroy();
    }
}