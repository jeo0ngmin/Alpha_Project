package com.medimate.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.medimate.MainActivity;
import com.medimate.auth.AuthRepository;

/** 로그인 여부를 보고 Login 또는 Main으로 보낸다 (README 7-1). 화면은 그리지 않는다. */
public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 카카오 사용자는 Firebase 사용자가 아니라서 재실행 시 Login으로 온다. Firebase 연결은 2차 (README 7-5)
        Class<?> next = new AuthRepository().isSignedIn() ? MainActivity.class : LoginActivity.class;
        startActivity(new Intent(this, next));
        finish();
    }
}
