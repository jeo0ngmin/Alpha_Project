package com.medimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.auth.FirebaseUser;
import com.medimate.R;
import com.medimate.auth.AuthRepository;
import com.medimate.auth.PhoneAuthHelper;
import com.medimate.tts.TtsManager;
import com.medimate.data.UserRepository;

/** 내 정보 (JE 화면, README 10-2 B5): 로그인 방식·이메일·전화번호 표시, 비밀번호 변경 메일, 로그아웃, 탈퇴. */
public class ProfileActivity extends AppCompatActivity {

    private static final String MSG_NO_PASSWORD = "구글, 전화번호, 카카오로 로그인하면 비밀번호가 없어서 바꿀 비밀번호가 없어요.";
    private static final String MSG_RESET_SENT = "비밀번호 변경 메일을 보냈어요. 메일함을 확인해 주세요.";

    private TtsManager tts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_profile);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.profile_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        tts = new TtsManager(this);
        AuthRepository auth = new AuthRepository();
        FirebaseUser user = auth.currentUser();
        // JE 화면의 "아이디 표시" 칸에 로그인 방식을 보여 준다
        TextView method = findViewById(R.id.btn_id);
        method.setText(getString(R.string.profile_login_method, methodLabel(user)));

        // 이메일·전화번호 칸: 실제 값을 보여 주고, 누르면 읽어 준다
        String email = user == null ? null : user.getEmail();
        String phone = user == null ? null : PhoneAuthHelper.toDisplay(user.getPhoneNumber());
        bindInfo(R.id.btn_email, getString(R.string.profile_email, orNone(email)));
        bindInfo(R.id.btn_phone, getString(R.string.profile_phone, orNone(phone)));

        // 비밀번호 변경: 이메일로 가입한 사용자에게만 변경 메일을 보낸다
        findViewById(R.id.btn_password).setOnClickListener(v -> changePassword(auth, user));

        findViewById(R.id.btn_logout).setOnClickListener(v -> signOut(auth));

        findViewById(R.id.btn_withdraw).setOnClickListener(v ->
                startActivity(new Intent(this, com.medimate.WithdrawActivity.class)));
    }

    private void bindInfo(int id, String text) {
        TextView view = findViewById(id);
        view.setText(text);
        view.setOnClickListener(v -> tts.speak(text));
    }

    private String orNone(String value) {
        return value == null || value.isEmpty() ? getString(R.string.profile_none) : value;
    }

    private void changePassword(AuthRepository auth, FirebaseUser user) {
        if (user == null || !UserRepository.PROVIDER_EMAIL.equals(AuthRepository.providerOf(user))
                || user.getEmail() == null) {
            tts.speak(MSG_NO_PASSWORD);
            return;
        }
        auth.sendPasswordReset(user.getEmail(), new AuthRepository.ResultCallback() {
            @Override
            public void onSuccess() {
                tts.speak(MSG_RESET_SENT);
            }

            @Override
            public void onError(String message) {
                tts.speak(message);
            }
        });
    }

    private void signOut(AuthRepository auth) {
        // Firebase 로그아웃 → 복약 데이터·알림 정리는 MedicationRepository가 계정 변경을 보고 처리한다 (README 9-6)
        auth.signOut();
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private String methodLabel(FirebaseUser user) {
        if (user == null) {
            // Firebase 사용자가 아니면 카카오 로그인이다 (README 7-5)
            return getString(R.string.profile_method_kakao);
        }
        switch (AuthRepository.providerOf(user)) {
            case UserRepository.PROVIDER_GOOGLE:
                return getString(R.string.profile_method_google);
            case UserRepository.PROVIDER_PHONE:
                return getString(R.string.profile_method_phone);
            case UserRepository.PROVIDER_EMAIL:
                return getString(R.string.profile_method_email);
            default:
                return getString(R.string.profile_method_unknown);
        }
    }

    @Override
    protected void onDestroy() {
        tts.shutdown();
        super.onDestroy();
    }
}
