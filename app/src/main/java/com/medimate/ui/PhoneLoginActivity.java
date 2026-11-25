package com.medimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

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
import com.medimate.auth.PhoneAuthHelper;
import com.medimate.tts.TtsManager;

/** 전화번호 로그인 화면 (README 7-4, B3): 번호 입력 → 인증번호 받기 → 인증번호 입력 → 확인. 안내는 TTS로도 읽어 준다. */
public class PhoneLoginActivity extends AppCompatActivity {

    private static final String MSG_ENTER_CODE = "인증번호 여섯 자리를 입력해 주세요.";
    private static final String MSG_SENDING = "인증번호를 보내고 있어요.";

    private TtsManager tts;
    private PhoneAuthHelper helper;
    private TextView status;
    private EditText codeInput;
    private Button sendButton;
    private Button verifyButton;
    private String verificationId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_phone_login);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.phone_login_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        tts = new TtsManager(this);
        helper = new PhoneAuthHelper(new AuthRepository());
        status = findViewById(R.id.text_phone_status);
        EditText phoneInput = findViewById(R.id.input_phone);
        codeInput = findViewById(R.id.input_code);
        sendButton = findViewById(R.id.button_send_code);
        verifyButton = findViewById(R.id.button_verify_code);

        sendButton.setOnClickListener(v -> {
            sendButton.setEnabled(false);
            say(MSG_SENDING);
            helper.startVerification(this, phoneInput.getText().toString(),
                    new PhoneAuthHelper.VerificationCallback() {
                        @Override
                        public void onCodeSent(String id, String message) {
                            verificationId = id;
                            sendButton.setEnabled(true);
                            codeInput.setVisibility(View.VISIBLE);
                            verifyButton.setVisibility(View.VISIBLE);
                            codeInput.requestFocus();
                            say(message);
                        }

                        @Override
                        public void onSignedIn(FirebaseUser user, boolean isNewUser) {
                            ProfileSync.ensure(user, PhoneLoginActivity.this::goToMain);
                        }

                        @Override
                        public void onError(String message) {
                            sendButton.setEnabled(true);
                            say(message);
                        }
                    });
        });

        verifyButton.setOnClickListener(v -> {
            String code = codeInput.getText().toString().trim();
            if (verificationId == null || code.length() != 6) {
                say(MSG_ENTER_CODE);
                return;
            }
            verifyButton.setEnabled(false);
            helper.verifyCode(verificationId, code, new AuthCallback() {
                @Override
                public void onSuccess(FirebaseUser user, boolean isNewUser) {
                    ProfileSync.ensure(user, PhoneLoginActivity.this::goToMain);
                }

                @Override
                public void onError(String message) {
                    verifyButton.setEnabled(true);
                    say(message);
                }
            });
        });
    }

    private void say(String message) {
        status.setText(message);
        tts.speak(message);
    }

    private void goToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        tts.shutdown();
        super.onDestroy();
    }
}
