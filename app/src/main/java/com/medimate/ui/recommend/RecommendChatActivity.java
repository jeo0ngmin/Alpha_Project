package com.medimate.ui.recommend;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.medimate.R;
import com.medimate.recommend.ProductLinks;
import com.medimate.tts.TtsManager;

/** 영양제 추천 챗봇 화면. 봇 답변은 TTS로도 읽어 준다. */
public class RecommendChatActivity extends AppCompatActivity {

    static final String MSG_NO_BROWSER = "링크를 열 수 있는 앱이 없어요.";

    private RecommendChatViewModel viewModel;
    private TtsManager tts;
    private ChatAdapter adapter;
    private RecyclerView list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_recommend_chat);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.chat_root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        tts = new TtsManager(this);
        viewModel = new ViewModelProvider(this).get(RecommendChatViewModel.class);

        list = findViewById(R.id.chat_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChatAdapter(this::openLink);
        list.setAdapter(adapter);

        EditText input = findViewById(R.id.input_message);
        Button send = findViewById(R.id.button_send);
        ProgressBar progress = findViewById(R.id.progress);

        send.setOnClickListener(v -> {
            viewModel.send(input.getText().toString());
            input.setText("");
        });

        viewModel.getMessages().observe(this, messages -> {
            adapter.submit(messages);
            list.scrollToPosition(adapter.getItemCount() - 1);
        });
        viewModel.getIsLoading().observe(this, loading -> {
            progress.setVisibility(loading ? View.VISIBLE : View.GONE);
            send.setEnabled(!loading);
        });
        viewModel.getSpeak().observe(this, tts::speak);
    }

    /** 쿠팡 검색 화면을 연다. 쿠팡 앱이 있으면 앱이, 없으면 브라우저가 연다 (README 8-9) */
    private void openLink(ProductLinks.Link link) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link.url)));
        } catch (ActivityNotFoundException e) {
            tts.speak(MSG_NO_BROWSER);
        }
    }

    @Override
    protected void onDestroy() {
        tts.shutdown();
        super.onDestroy();
    }
}
