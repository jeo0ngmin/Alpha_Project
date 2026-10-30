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
        AuthRepository auth = new AuthRepository();
        if (auth.currentUser() != null) {
            // D1 이전에 로그인해 둔 사용자도 프로필이 생기게 한다. 기다리지 않는다
            ProfileSync.ensure(auth.currentUser(), () -> { });
        }
        Class<?> next = auth.isSignedIn() ? MainActivity.class : LoginActivity.class;
        startActivity(new Intent(this, next));
        finish();
    }
}
