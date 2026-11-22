package com.medimate;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseUser;
import com.medimate.auth.AuthRepository;
import com.medimate.data.UserRepository;
import com.medimate.data.firestore.FirestoreUserRepository;
import com.medimate.data.medication.MedicationRepository;
import com.medimate.tts.TtsManager;
import com.medimate.ui.LoginActivity;

/**
 * 회원 탈퇴 (JE 화면, README 7-6, 9-5). 이 화면 자체가 "정말 탈퇴할까요?" 확인이다.
 * 탈퇴 버튼: users/{uid} 아래 데이터 삭제 → Firebase 계정 삭제 → 기기 복약 데이터·알림 정리(README 9-6) → 로그인 화면.
 */
public class WithdrawActivity extends AppCompatActivity {

    private static final String MSG_INTRO = "Medimate 계정을 탈퇴하시겠습니까? 탈퇴하시면 모든 데이터가 삭제되며, "
            + "이후 복구할 수 없습니다. 탈퇴하려면 탈퇴하기 버튼을, 아니면 취소를 눌러 주세요.";
    private static final String MSG_DELETING = "탈퇴하고 있어요. 잠시만 기다려 주세요.";
    private static final String MSG_DONE = "탈퇴했어요. 그동안 이용해 주셔서 감사해요.";
    private static final String MSG_KAKAO = "카카오 로그인은 저장된 내 데이터가 없어서 로그아웃만 하면 돼요.";

    private final AuthRepository auth = new AuthRepository();
    private TtsManager tts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_withdraw);
        tts = new TtsManager(this);

        // 뒤로가기
        findViewById(R.id.btn_back)
                .setOnClickListener(v -> finish());

        // X 버튼
        findViewById(R.id.btn_close)
                .setOnClickListener(v -> finish());

        // 취소
        findViewById(R.id.btn_cancel)
                .setOnClickListener(v -> finish());

        // 탈퇴
        findViewById(R.id.btn_withdraw)
                .setOnClickListener(v -> deleteAccount());

        if (savedInstanceState == null) {
            tts.speak(MSG_INTRO);
        }
    }

    private void deleteAccount() {
        FirebaseUser user = auth.currentUser();
        if (user == null) {
            tts.speak(MSG_KAKAO);
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
        findViewById(R.id.btn_withdraw).setEnabled(enabled);
        findViewById(R.id.btn_cancel).setEnabled(enabled);
    }

    private void goToLogin(String message) {
        Intent intent = LoginActivity.newIntent(this, message);
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
