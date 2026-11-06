package com.medimate;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class WelcomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);

        // 회원가입 화면 이동
        findViewById(R.id.btn_signup).setOnClickListener(v -> {
            Intent intent = new Intent(
                    WelcomeActivity.this,
                    SignupActivity.class
            );
            startActivity(intent);
        });

        // 로그인 화면 이동
        findViewById(R.id.btn_login).setOnClickListener(v -> {
            Intent intent = new Intent(
                    WelcomeActivity.this,
                    LoginActivity.class
            );
            startActivity(intent);
        });
    }
}