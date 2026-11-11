package com.medimate;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class HomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);


        // =========================
        // 현재 홈 탭 강조
        // =========================

        TextView homeText = findViewById(R.id.tv_nav_home);
        ImageView homeIcon = findViewById(R.id.iv_nav_home);

        homeText.setTextColor(
                getColor(R.color.medimate_sage_dark)
        );

        homeIcon.setColorFilter(
                getColor(R.color.medimate_sage_dark)
        );


        // =========================
        // 약 봉투 분석
        // =========================

        findViewById(R.id.card_bag_analysis)
                .setOnClickListener(v -> {

                    Intent intent = new Intent(
                            HomeActivity.this,
                            MainActivity.class
                    );

                    startActivity(intent);
                });


        // =========================
        // 알약 분석
        // =========================

        findViewById(R.id.card_pill_analysis)
                .setOnClickListener(v -> {

                    Intent intent = new Intent(
                            HomeActivity.this,
                            PillCaptureActivity.class
                    );

                    startActivity(intent);
                });


        // =========================
        // 복약 일정 전체 보기
        // =========================

        findViewById(R.id.tv_view_reminder)
                .setOnClickListener(v ->
                        openReminder()
                );


        // =========================
        // 하단 네비게이션
        // =========================

        // 홈
        // 현재 화면이므로 이동하지 않음
        findViewById(R.id.nav_home)
                .setOnClickListener(v -> {
                    // 현재 홈 화면
                });


        // 복약알림
        findViewById(R.id.nav_reminder)
                .setOnClickListener(v ->
                        openReminder()
                );


        // 분석
        findViewById(R.id.nav_analysis)
                .setOnClickListener(v -> {

                    Intent intent = new Intent(
                            HomeActivity.this,
                            PillCaptureActivity.class
                    );

                    startActivity(intent);
                });


        // 챗봇
        findViewById(R.id.nav_chatbot)
                .setOnClickListener(v -> {

                    Intent intent = new Intent(
                            HomeActivity.this,
                            ChatbotActivity.class
                    );

                    startActivity(intent);
                });
    }


    // =========================
    // 복약알림 화면 이동
    // =========================

    private void openReminder() {

        Intent intent = new Intent(
                HomeActivity.this,
                MedicationReminderActivity.class
        );

        startActivity(intent);
    }
}