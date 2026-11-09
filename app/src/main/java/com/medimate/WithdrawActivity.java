package com.medimate;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class WithdrawActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_withdraw);

        // 뒤로가기
        findViewById(R.id.btn_back)
                .setOnClickListener(v -> finish());

        // X 버튼
        findViewById(R.id.btn_close)
                .setOnClickListener(v -> finish());

        // 취소
        findViewById(R.id.btn_cancel)
                .setOnClickListener(v -> finish());
    }
}