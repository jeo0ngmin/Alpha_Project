package com.medimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.auth.FirebaseUser;
import com.medimate.MainActivity;
import com.medimate.R;
import com.medimate.auth.AuthCallback;
import com.medimate.auth.AuthRepository;
import com.medimate.auth.GoogleSignInHelper;
import com.medimate.auth.KakaoSignInHelper;
import com.medimate.tts.TtsManager;

/** 로그인 화면: 버튼 세 개 (전화번호 / 구글 / 카카오) (README 7-1). 실패 사유는 TTS로 읽어 준다. */
public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";

    private TtsManager tts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_login);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.login_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // JE 화면의 뒤로가기·회원가입 글자 (원래 JE의 com.medimate.LoginActivity에 있던 것)
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.tv_signup).setOnClickListener(v ->
                startActivity(new Intent(this, com.medimate.SignupActivity.class)));

        tts = new TtsManager(this);
        AuthRepository repository = new AuthRepository();

        findViewById(R.id.btn_phone).setOnClickListener(v ->
                startActivity(new Intent(this, PhoneLoginActivity.class)));

        findViewById(R.id.btn_google).setOnClickListener(v ->
                new GoogleSignInHelper(repository).signIn(this, new AuthCallback() {
                    @Override
                    public void onSuccess(FirebaseUser user, boolean isNewUser) {
                        ProfileSync.ensure(user, LoginActivity.this::goToMain);
                    }

                    @Override
                    public void onError(String message) {
                        tts.speak(message);
                    }
                }));

        findViewById(R.id.btn_kakao).setOnClickListener(v ->
                new KakaoSignInHelper().signIn(this, new KakaoSignInHelper.KakaoCallback() {
                    @Override
                    public void onSuccess(long kakaoId, String nickname) {
                        Log.i(TAG, "카카오 로그인: id=" + kakaoId + " nickname=" + nickname);
                        goToMain();
                    }

                    @Override
                    public void onError(String message) {
                        tts.speak(message);
                    }
                }));
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    protected void onDestroy() {
        tts.shutdown();
        super.onDestroy();
    }
}
