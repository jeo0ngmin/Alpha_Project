package com.medimate;

import com.medimate.auth.AuthCallback;
import com.medimate.auth.AuthRepository;
import com.medimate.auth.SignupValidator;
import com.medimate.tts.TtsManager;
import com.medimate.ui.LoginActivity;
import com.medimate.ui.ProfileSync;

import android.content.Intent;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseUser;

/**
 * [JE 화면] 이메일 회원가입. 입력을 확인하고 Firebase에 이메일·비밀번호로 가입한 뒤 프로필을 만들고 메인으로 간다.
 * 문제가 있으면 읽어 준다. Firebase 콘솔에서 "이메일/비밀번호" 로그인 방법을 사용 설정해야 한다.
 */
public class SignupActivity extends AppCompatActivity {

    private static final String MSG_SIGNING_UP = "가입하고 있어요. 잠시만 기다려 주세요.";
    private static final String MSG_DONE = "가입했어요. 메디메이트에 오신 것을 환영해요.";

    private final AuthRepository auth = new AuthRepository();
    private TtsManager tts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_signup);
        tts = new TtsManager(this);

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

        // 가입하기
        findViewById(R.id.btn_signup)
                .setOnClickListener(v -> signUp());
    }

    private void signUp() {
        String email = ((EditText) findViewById(R.id.et_email)).getText().toString().trim();
        String password = ((EditText) findViewById(R.id.et_password)).getText().toString();
        String confirm = ((EditText) findViewById(R.id.et_password_confirm)).getText().toString();
        boolean terms = ((CheckBox) findViewById(R.id.check_terms)).isChecked();

        String problem = SignupValidator.check(email, password, confirm, terms);
        if (problem != null) {
            tts.speak(problem);
            return;
        }
        findViewById(R.id.btn_signup).setEnabled(false);
        tts.speak(MSG_SIGNING_UP);
        auth.signUpWithEmail(email, password, new AuthCallback() {
            @Override
            public void onSuccess(FirebaseUser user, boolean isNewUser) {
                tts.speak(MSG_DONE);
                ProfileSync.ensure(user, () -> {
                    Intent intent = new Intent(SignupActivity.this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                });
            }

            @Override
            public void onError(String message) {
                findViewById(R.id.btn_signup).setEnabled(true);
                tts.speak(message);
            }
        });
    }

    @Override
    protected void onDestroy() {
        tts.shutdown();
        super.onDestroy();
    }
}
