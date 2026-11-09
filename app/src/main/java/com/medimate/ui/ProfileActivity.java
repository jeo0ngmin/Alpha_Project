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
import com.medimate.data.UserRepository;

/** 내 정보 화면 틀 (README 10-2 B5): 로그인 방식 표시와 로그아웃 버튼만. */
public class ProfileActivity extends AppCompatActivity {

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

        AuthRepository auth = new AuthRepository();
        TextView method = findViewById(R.id.text_login_method);
        method.setText(getString(R.string.profile_login_method, methodLabel(auth.currentUser())));

        findViewById(R.id.button_sign_out).setOnClickListener(v -> {
            // Firebase 로그아웃 → 복약 데이터·알림 정리는 MedicationRepository가 계정 변경을 보고 처리한다 (README 9-6)
            auth.signOut();
            Intent intent = new Intent(this, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
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
}
