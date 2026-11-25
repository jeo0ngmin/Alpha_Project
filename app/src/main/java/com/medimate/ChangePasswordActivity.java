package com.medimate;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

public class ChangePasswordActivity extends AppCompatActivity {

    private TextInputLayout tilCurrentPassword;
    private TextInputLayout tilNewPassword;
    private TextInputLayout tilConfirmPassword;

    private TextInputEditText etCurrentPassword;
    private TextInputEditText etNewPassword;
    private TextInputEditText etConfirmPassword;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_change_password);


        // =========================
        // View 연결
        // =========================

        tilCurrentPassword =
                findViewById(R.id.til_current_password);

        tilNewPassword =
                findViewById(R.id.til_new_password);

        tilConfirmPassword =
                findViewById(R.id.til_confirm_password);


        etCurrentPassword =
                findViewById(R.id.et_current_password);

        etNewPassword =
                findViewById(R.id.et_new_password);

        etConfirmPassword =
                findViewById(R.id.et_confirm_password);


        // =========================
        // 뒤로가기
        // =========================

        findViewById(R.id.btn_back)
                .setOnClickListener(v -> finish());


        // =========================
        // 비밀번호 변경
        // =========================

        findViewById(R.id.btn_change_password)
                .setOnClickListener(v ->
                        validateAndChangePassword()
                );
    }


    // =========================
    // 입력 검증
    // =========================

    private void validateAndChangePassword() {

        String currentPassword =
                getText(etCurrentPassword);

        String newPassword =
                getText(etNewPassword);

        String confirmPassword =
                getText(etConfirmPassword);


        // 기존 오류 초기화
        tilCurrentPassword.setError(null);
        tilNewPassword.setError(null);
        tilConfirmPassword.setError(null);


        // =========================
        // 현재 비밀번호
        // =========================

        if (currentPassword.isEmpty()) {

            tilCurrentPassword.setError(
                    "현재 비밀번호를 입력해 주세요."
            );

            return;
        }


        // =========================
        // 새 비밀번호
        // =========================

        if (newPassword.isEmpty()) {

            tilNewPassword.setError(
                    "새 비밀번호를 입력해 주세요."
            );

            return;
        }


        // 기존 비밀번호와 같은 경우
        if (currentPassword.equals(newPassword)) {

            tilNewPassword.setError(
                    "현재 비밀번호와 다른 비밀번호를 입력해 주세요."
            );

            return;
        }


        // =========================
        // 새 비밀번호 확인
        // =========================

        if (confirmPassword.isEmpty()) {

            tilConfirmPassword.setError(
                    "새 비밀번호를 다시 입력해 주세요."
            );

            return;
        }


        // 비밀번호 불일치
        if (!newPassword.equals(confirmPassword)) {

            tilConfirmPassword.setError(
                    "새 비밀번호가 일치하지 않습니다."
            );

            return;
        }


        /*
         * 현재 단계:
         * 화면 + 입력 검증까지만 구현.
         *
         * 추후:
         * 1. 현재 비밀번호 서버 검증
         * 2. 새 비밀번호 변경 API 호출
         * 3. 변경 성공 처리
         */


        Toast.makeText(
                this,
                "비밀번호 변경 정보를 확인했습니다.",
                Toast.LENGTH_SHORT
        ).show();
    }


    // =========================
    // EditText 문자열 가져오기
    // =========================

    private String getText(
            TextInputEditText editText
    ) {

        if (editText.getText() == null) {

            return "";
        }

        return editText
                .getText()
                .toString()
                .trim();
    }
}