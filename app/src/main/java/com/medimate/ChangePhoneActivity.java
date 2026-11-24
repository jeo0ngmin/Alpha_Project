package com.medimate;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

public class ChangePhoneActivity extends AppCompatActivity {

    private TextInputLayout tilNewPhone;
    private TextInputLayout tilVerificationCode;

    private TextInputEditText etNewPhone;
    private TextInputEditText etVerificationCode;

    private boolean verificationCodeSent = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_change_phone);


        // =========================
        // View 연결
        // =========================

        tilNewPhone =
                findViewById(R.id.til_new_phone);

        tilVerificationCode =
                findViewById(R.id.til_verification_code);

        etNewPhone =
                findViewById(R.id.et_new_phone);

        etVerificationCode =
                findViewById(R.id.et_verification_code);


        // =========================
        // 뒤로가기
        // =========================

        findViewById(R.id.btn_back)
                .setOnClickListener(v -> finish());


        // =========================
        // 인증번호 받기
        // =========================

        findViewById(R.id.btn_send_code)
                .setOnClickListener(v ->
                        sendVerificationCode()
                );


        // =========================
        // 전화번호 변경
        // =========================

        findViewById(R.id.btn_change_phone)
                .setOnClickListener(v ->
                        validateAndChangePhone()
                );
    }


    // =========================
    // 인증번호 요청
    // =========================

    private void sendVerificationCode() {

        String phone =
                etNewPhone.getText() == null
                        ? ""
                        : etNewPhone
                        .getText()
                        .toString()
                        .trim();


        if (phone.isEmpty()) {

            tilNewPhone.setError(
                    "새 전화번호를 입력해 주세요."
            );

            return;
        }


        if (phone.length() < 10) {

            tilNewPhone.setError(
                    "전화번호를 정확히 입력해 주세요."
            );

            return;
        }


        tilNewPhone.setError(null);

        verificationCodeSent = true;


        /*
         * 현재는 실제 SMS 발송을 하지 않음.
         * 추후 인증 API 연결 예정.
         */

        Toast.makeText(
                this,
                "인증번호를 전송했습니다.",
                Toast.LENGTH_SHORT
        ).show();
    }


    // =========================
    // 변경하기
    // =========================

    private void validateAndChangePhone() {

        String phone =
                etNewPhone.getText() == null
                        ? ""
                        : etNewPhone
                        .getText()
                        .toString()
                        .trim();


        String verificationCode =
                etVerificationCode.getText() == null
                        ? ""
                        : etVerificationCode
                        .getText()
                        .toString()
                        .trim();


        // 전화번호
        if (phone.isEmpty()) {

            tilNewPhone.setError(
                    "새 전화번호를 입력해 주세요."
            );

            return;

        } else {

            tilNewPhone.setError(null);
        }


        // 인증번호 요청 여부
        if (!verificationCodeSent) {

            Toast.makeText(
                    this,
                    "먼저 인증번호를 받아 주세요.",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }


        // 인증번호
        if (verificationCode.isEmpty()) {

            tilVerificationCode.setError(
                    "인증번호를 입력해 주세요."
            );

            return;

        } else {

            tilVerificationCode.setError(null);
        }


        if (verificationCode.length() < 4) {

            tilVerificationCode.setError(
                    "인증번호를 정확히 입력해 주세요."
            );

            return;
        }


        /*
         * 현재 단계:
         * 화면 + 입력 검증까지 구현.
         *
         * 추후:
         * 1. SMS 인증번호 실제 검증
         * 2. 서버 전화번호 변경
         * 3. ProfileActivity에 새 번호 반영
         */

        Toast.makeText(
                this,
                "전화번호 변경 정보를 확인했습니다.",
                Toast.LENGTH_SHORT
        ).show();
    }
}