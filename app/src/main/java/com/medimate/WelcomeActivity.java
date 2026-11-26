package com.medimate;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.medimate.ui.EdgeToEdgeHelper;
import com.medimate.ui.LoginActivity;

public class WelcomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_welcome);


        // =========================
        // 시스템 바 여백 처리
        // =========================

        EdgeToEdgeHelper.padForSystemBars(
                findViewById(R.id.welcome_root)
        );


        // =========================
        // 회원가입 화면 이동
        // =========================

        findViewById(R.id.btn_signup)
                .setOnClickListener(v -> {

                    Intent intent =
                            new Intent(
                                    WelcomeActivity.this,
                                    SignupActivity.class
                            );

                    startActivity(intent);
                });


        // =========================
        // 로그인 화면 이동
        // =========================

        findViewById(R.id.btn_login)
                .setOnClickListener(v -> {

                    Intent intent =
                            new Intent(
                                    WelcomeActivity.this,
                                    LoginActivity.class
                            );

                    startActivity(intent);
                });
    }
}