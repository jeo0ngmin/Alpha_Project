package com.medimate;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;
import com.medimate.recommend.ProductLinks;
import com.medimate.tts.TtsManager;
import com.medimate.ui.EdgeToEdgeHelper;
import com.medimate.ui.recommend.ChatAdapter;
import com.medimate.ui.recommend.RecommendChatViewModel;

/**
 * [JE 화면] 챗봇. 증상을 말하면 영양제 성분을 추천한다 (README 8장, RecommendChatViewModel).
 * 빠른 질문 버튼은 예시 문장을 입력창에 넣는다. 봇 답변은 TTS로도 읽어 준다.
 */
public class ChatbotActivity extends AppCompatActivity {

    private static final String MSG_NO_BROWSER = "링크를 열 수 있는 앱이 없어요.";

    private RecommendChatViewModel viewModel;
    private TtsManager tts;
    private EditText etChatInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chatbot);
        EdgeToEdgeHelper.padForSystemBars(findViewById(R.id.chatbot_root));

        tts = new TtsManager(this);
        viewModel = new ViewModelProvider(this).get(RecommendChatViewModel.class);
        etChatInput = findViewById(R.id.et_chat_input);
        NestedScrollView scroll = findViewById(R.id.chat_scroll);
        View send = findViewById(R.id.btn_send);
        View progress = findViewById(R.id.progress_chat);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        // 빠른 질문: 예시 문장을 입력창에 넣는다
        findViewById(R.id.chip_dose).setOnClickListener(v ->
                setQuickQuestion(getString(R.string.chat_quick_dose_question)));
        findViewById(R.id.chip_side_effect).setOnClickListener(v ->
                setQuickQuestion(getString(R.string.chat_quick_side_effect_question)));
        findViewById(R.id.chip_together).setOnClickListener(v ->
                setQuickQuestion(getString(R.string.chat_quick_together_question)));
        findViewById(R.id.chip_storage).setOnClickListener(v ->
                setQuickQuestion(getString(R.string.chat_quick_storage_question)));

        RecyclerView list = findViewById(R.id.chat_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        ChatAdapter adapter = new ChatAdapter(this::openLink);
        list.setAdapter(adapter);

        send.setOnClickListener(v -> {
            String question = etChatInput.getText().toString().trim();
            if (question.isEmpty()) {
                say(getString(R.string.chat_empty_question));
                return;
            }
            viewModel.send(question);
            etChatInput.setText("");
        });

        viewModel.getMessages().observe(this, messages -> {
            adapter.submit(messages);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
        viewModel.getIsLoading().observe(this, loading -> {
            progress.setVisibility(loading ? View.VISIBLE : View.GONE);
            send.setEnabled(!loading);
            send.setAlpha(loading ? 0.4f : 1f);
        });
        viewModel.getSpeak().observe(this, tts::speak);

        bindBottomNav();
    }

    private void bindBottomNav() {
        findViewById(R.id.nav_home).setOnClickListener(v -> {
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });
        findViewById(R.id.nav_reminder).setOnClickListener(v -> {
            startActivity(MedicationReminderActivity.newIntent(this, null));
            finish();
        });
        findViewById(R.id.nav_analysis).setOnClickListener(v ->
                startActivity(new Intent(this, PillCaptureActivity.class)));
        // 챗봇은 현재 화면
        findViewById(R.id.nav_chatbot).setOnClickListener(v -> { });
        TextView chatbotText = findViewById(R.id.tv_nav_chatbot);
        chatbotText.setTextColor(getColor(R.color.medimate_sage_dark));
    }

    /** 빠른 질문 버튼을 누르면 질문 내용을 입력창에 넣는다 */
    private void setQuickQuestion(String question) {
        etChatInput.setText(question);
        etChatInput.setSelection(etChatInput.getText().length());
        etChatInput.requestFocus();
        tts.speak(question + ". 전송 버튼을 누르면 물어볼게요.");
    }

    /** 쿠팡 검색 화면을 연다. 쿠팡 앱이 있으면 앱이, 없으면 브라우저가 연다 (README 8-9) */
    private void openLink(ProductLinks.Link link) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link.url)));
        } catch (ActivityNotFoundException e) {
            say(MSG_NO_BROWSER);
        }
    }

    private void say(String message) {
        Snackbar.make(findViewById(R.id.chatbot_root), message, Snackbar.LENGTH_LONG).show();
        tts.speak(message);
    }

    @Override
    protected void onDestroy() {
        tts.shutdown();
        super.onDestroy();
    }
}
