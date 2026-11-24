package com.medimate;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class WithdrawActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_withdraw);


        // =========================
        // 뒤로가기
        // =========================

        findViewById(R.id.btn_back)
                .setOnClickListener(v -> finish());


        // =========================
        // X 버튼
        // =========================

        findViewById(R.id.btn_close)
                .setOnClickListener(v -> finish());


        // =========================
        // 취소
        // =========================

        findViewById(R.id.btn_cancel)
                .setOnClickListener(v -> finish());


        // =========================
        // 회원 탈퇴
        // =========================

        findViewById(R.id.btn_withdraw)
                .setOnClickListener(v ->
                        showWithdrawDialog()
                );
    }


    // =========================
    // 탈퇴 최종 확인창
    // =========================

    private void showWithdrawDialog() {

        new MaterialAlertDialogBuilder(this)
                .setTitle("회원 탈퇴")
                .setMessage(
                        "정말 Medimate를 탈퇴하시겠습니까?\n" +
                                "탈퇴 후 삭제된 정보는 복구할 수 없습니다."
                )

                // 취소
                .setNegativeButton(
                        "취소",
                        (dialog, which) ->
                                dialog.dismiss()
                )

                // 탈퇴하기
                .setPositiveButton(
                        "탈퇴하기",
                        (dialog, which) -> {

                            /*
                             * 현재는 실제 계정 삭제 기능이
                             * 연결되지 않은 상태입니다.
                             *
                             * 나중에는 여기서
                             * 서버의 회원 탈퇴 API를 호출하고,
                             * 성공했을 때 completeWithdraw()를
                             * 실행하면 됩니다.
                             */

                            completeWithdraw();
                        }
                )

                .show();
    }


    // =========================
    // 탈퇴 완료 처리
    // =========================

    private void completeWithdraw() {

        Toast.makeText(
                this,
                "회원 탈퇴가 완료되었습니다.",
                Toast.LENGTH_SHORT
        ).show();


        // Welcome 화면으로 이동
        Intent intent = new Intent(
                WithdrawActivity.this,
                WelcomeActivity.class
        );


        // 이전 화면 기록 모두 제거
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
        );


        startActivity(intent);
    }
}