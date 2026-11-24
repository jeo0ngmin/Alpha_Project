package com.medimate;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class ProfileActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_profile);


        // =========================
        // 뒤로가기
        // =========================

        findViewById(R.id.btn_back)
                .setOnClickListener(v -> finish());


        // =========================
        // 전화번호 변경
        // =========================

        findViewById(R.id.btn_phone)
                .setOnClickListener(v -> {

                    Intent intent = new Intent(
                            ProfileActivity.this,
                            ChangePhoneActivity.class
                    );

                    startActivity(intent);
                });


        // =========================
        // 비밀번호 변경
        // =========================

        findViewById(R.id.btn_password)
                .setOnClickListener(v -> {

                    Intent intent = new Intent(
                            ProfileActivity.this,
                            ChangePasswordActivity.class
                    );

                    startActivity(intent);
                });


        // =========================
        // 회원 탈퇴
        // =========================

        findViewById(R.id.btn_withdraw)
                .setOnClickListener(v -> {

                    Intent intent = new Intent(
                            ProfileActivity.this,
                            WithdrawActivity.class
                    );

                    startActivity(intent);
                });
    }
}