package com.medimate.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseUser;
import com.medimate.R;
import com.medimate.auth.AuthRepository;
import com.medimate.data.UserRepository;
import com.medimate.data.firestore.FirestoreUserRepository;
import com.medimate.data.medication.MedicationRepository;
import com.medimate.tts.TtsManager;

/** 내 정보 (README 10-2 B5): 로그인 방식 표시, 로그아웃, 탈퇴 (README 7-6, 9-5). */
public class ProfileActivity extends AppCompatActivity {

    private static final String MSG_CONFIRM = "탈퇴하면 내 약, 복약 일정, 복용 기록, 추천 기록이 모두 지워지고 "
            + "되돌릴 수 없어요. 탈퇴할까요?";
    private static final String MSG_DELETING = "탈퇴하고 있어요. 잠시만 기다려 주세요.";
    private static final String MSG_DONE = "탈퇴했어요. 그동안 이용해 주셔서 감사해요.";
    private static final String MSG_KAKAO = "카카오 로그인은 저장된 내 데이터가 없어서 로그아웃만 하면 돼요.";

    private final AuthRepository auth = new AuthRepository();
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
        TextView method = findViewById(R.id.text_login_method);
        method.setText(getString(R.string.profile_login_method, methodLabel(auth.currentUser())));

        findViewById(R.id.button_sign_out).setOnClickListener(v -> {
            // Firebase 로그아웃 → 복약 데이터·알림 정리는 MedicationRepository가 계정 변경을 보고 처리한다 (README 9-6)
            auth.signOut();
            goToLogin(null);
        });
        findViewById(R.id.button_delete_account).setOnClickListener(v -> confirmDelete());
    }

    private void confirmDelete() {
        if (auth.currentUser() == null) {
            tts.speak(MSG_KAKAO);
            return;
        }
        tts.speak(MSG_CONFIRM);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.profile_delete_account)
                .setMessage(MSG_CONFIRM)
                .setPositiveButton(R.string.profile_delete_confirm, (d, w) -> deleteAccount())
                .setNegativeButton(R.string.profile_delete_cancel, null)
                .show();
    }

    private void deleteAccount() {
        FirebaseUser user = auth.currentUser();
        if (user == null) {
            return;
        }
        // 데이터를 지운 뒤 계정 삭제가 막히지 않도록, 로그인한 지 오래됐으면 먼저 다시 로그인하게 한다
        if (!auth.hasRecentLogin()) {
            auth.signOut();
            goToLogin(AuthRepository.MSG_REAUTH);
            return;
        }
        setButtonsEnabled(false);
        tts.speak(MSG_DELETING);
        MedicationRepository medication = MedicationRepository.get(this);
        medication.stopSync();
        new FirestoreUserRepository().deleteAllData(user.getUid(), new UserRepository.DeleteCallback() {
            @Override
            public void onDeleted() {
                auth.deleteAccount(new AuthRepository.DeleteCallback() {
                    @Override
                    public void onDeleted() {
                        goToLogin(MSG_DONE);
                    }

                    @Override
                    public void onError(String message) {
                        if (AuthRepository.MSG_REAUTH.equals(message)) {
                            goToLogin(message);
                        } else {
                            setButtonsEnabled(true);
                            tts.speak(message);
                        }
                    }
                });
            }

            @Override
            public void onError(String message) {
                medication.requestSync();
                setButtonsEnabled(true);
                tts.speak(message);
            }
        });
    }

    private void setButtonsEnabled(boolean enabled) {
        findViewById(R.id.button_sign_out).setEnabled(enabled);
        findViewById(R.id.button_delete_account).setEnabled(enabled);
    }

    private void goToLogin(String message) {
        Intent intent = LoginActivity.newIntent(this, message);
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
