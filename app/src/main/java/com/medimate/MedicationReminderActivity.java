package com.medimate;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MedicationReminderActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 복약알림 XML 연결
        setContentView(R.layout.activity_medication_reminder);


        // =========================
        // 현재 복약알림 탭 강조
        // =========================

        TextView reminderText =
                findViewById(R.id.tv_nav_reminder);

        reminderText.setTextColor(
                getColor(R.color.medimate_sage_dark)
        );


        // =========================
        // 하단 네비게이션
        // =========================

        // 홈
        findViewById(R.id.nav_home)
                .setOnClickListener(v -> {

                    Intent intent = new Intent(
                            MedicationReminderActivity.this,
                            HomeActivity.class
                    );

                    startActivity(intent);
                    finish();
                });


        // 복약알림
        // 현재 화면이므로 이동하지 않음
        findViewById(R.id.nav_reminder)
                .setOnClickListener(v -> {
                    // 현재 복약알림 화면
                });


        // 분석
        findViewById(R.id.nav_analysis)
                .setOnClickListener(v -> {

                    Intent intent = new Intent(
                            MedicationReminderActivity.this,
                            PillCaptureActivity.class
                    );

                    startActivity(intent);
                });


        // 챗봇
        findViewById(R.id.nav_chatbot)
                .setOnClickListener(v -> {

                    Intent intent = new Intent(
                            MedicationReminderActivity.this,
                            ChatbotActivity.class
                    );

                    startActivity(intent);
                });


        // =========================
        // 상단 알림 추가 버튼
        // =========================

        findViewById(R.id.btn_add_reminder)
                .setOnClickListener(v -> {

                    Toast.makeText(
                            MedicationReminderActivity.this,
                            "복약 알림 추가 기능은 다음 단계에서 연결할 예정입니다.",
                            Toast.LENGTH_SHORT
                    ).show();
                });


        // =========================
        // 하단 복용 알림 추가 버튼
        // =========================

        findViewById(R.id.btn_add_med_schedule)
                .setOnClickListener(v -> {

                    Toast.makeText(
                            MedicationReminderActivity.this,
                            "복용 시간을 추가하는 화면은 다음 단계에서 연결할 예정입니다.",
                            Toast.LENGTH_SHORT
                    ).show();
                });
    }
}