package com.medimate;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class SignupActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_signup);

        // 뒤로가기
        findViewById(R.id.btn_back)
                .setOnClickListener(v -> finish());

        // 로그인 화면 이동
        findViewById(R.id.tv_login)
                .setOnClickListener(v -> {

                    Intent intent = new Intent(
                            SignupActivity.this,
                            LoginActivity.class
                    );

                    startActivity(intent);
                });
    }
}