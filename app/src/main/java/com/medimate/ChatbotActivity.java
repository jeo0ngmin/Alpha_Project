package com.medimate;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class ChatbotActivity extends AppCompatActivity {

    private EditText etChatInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chatbot);

        etChatInput = findViewById(R.id.et_chat_input);


        // =========================
        // 뒤로가기
        // =========================

        findViewById(R.id.btn_back)
                .setOnClickListener(v -> finish());


        // =========================
        // 빠른 질문
        // =========================

        findViewById(R.id.chip_dose)
                .setOnClickListener(v ->
                        setQuickQuestion(
                                getString(R.string.chat_quick_dose_question)
                        )
                );

        findViewById(R.id.chip_side_effect)
                .setOnClickListener(v ->
                        setQuickQuestion(
                                getString(R.string.chat_quick_side_effect_question)
                        )
                );

        findViewById(R.id.chip_together)
                .setOnClickListener(v ->
                        setQuickQuestion(
                                getString(R.string.chat_quick_together_question)
                        )
                );

        findViewById(R.id.chip_storage)
                .setOnClickListener(v ->
                        setQuickQuestion(
                                getString(R.string.chat_quick_storage_question)
                        )
                );


        // =========================
        // 질문 전송
        // =========================

        findViewById(R.id.btn_send)
                .setOnClickListener(v -> {

                    String question =
                            etChatInput.getText()
                                    .toString()
                                    .trim();

                    if (question.isEmpty()) {

                        Toast.makeText(
                                ChatbotActivity.this,
                                R.string.chat_empty_question,
                                Toast.LENGTH_SHORT
                        ).show();

                        return;
                    }

                    /*
                     * 현재는 UI 테스트 단계.
                     * 나중에 여기에 실제 챗봇 API를 연결하면 됨.
                     */
                    Toast.makeText(
                            ChatbotActivity.this,
                            getString(R.string.chat_question_sent),
                            Toast.LENGTH_SHORT
                    ).show();

                    etChatInput.setText("");
                });


        // =========================
        // 하단 네비게이션
        // =========================

        // 홈
        findViewById(R.id.nav_home)
                .setOnClickListener(v -> {

                    Intent intent = new Intent(
                            ChatbotActivity.this,
                            HomeActivity.class
                    );

                    startActivity(intent);
                    finish();
                });


        // 복약알림
        findViewById(R.id.nav_reminder)
                .setOnClickListener(v -> {

                    Intent intent = new Intent(
                            ChatbotActivity.this,
                            MedicationReminderActivity.class
                    );

                    startActivity(intent);
                    finish();
                });


        // 분석
        findViewById(R.id.nav_analysis)
                .setOnClickListener(v -> {

                    Intent intent = new Intent(
                            ChatbotActivity.this,
                            PillCaptureActivity.class
                    );

                    startActivity(intent);
                });


        // 챗봇
        // 현재 화면이므로 이동하지 않음
        findViewById(R.id.nav_chatbot)
                .setOnClickListener(v -> {
                    // 현재 챗봇 화면
                });


        // 현재 챗봇 메뉴 강조
        TextView chatbotText =
                findViewById(R.id.tv_nav_chatbot);

        chatbotText.setTextColor(
                getColor(R.color.medimate_sage_dark)
        );
    }


    /*
     * 빠른 질문 버튼을 누르면
     * 질문 내용을 입력창에 자동으로 넣음
     */
    private void setQuickQuestion(String question) {

        etChatInput.setText(question);

        etChatInput.setSelection(
                etChatInput.getText().length()
        );

        etChatInput.requestFocus();
    }
}