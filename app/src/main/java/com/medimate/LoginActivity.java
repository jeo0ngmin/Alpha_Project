package com.medimate;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class LoginActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_login);

        // 뒤로가기
        findViewById(R.id.btn_back)
                .setOnClickListener(v -> finish());

        // 회원가입 이동
        findViewById(R.id.tv_signup)
                .setOnClickListener(v -> {

                    Intent intent = new Intent(
                            LoginActivity.this,
                            SignupActivity.class
                    );

                    startActivity(intent);
                });
    }
}