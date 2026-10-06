package com.medimate.ui.recommend;

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
import com.medimate.tts.TtsManager;

/** 영양제 추천 챗봇 화면. 봇 답변은 TTS로도 읽어 준다. */
public class RecommendChatActivity extends AppCompatActivity {

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
        adapter = new ChatAdapter();
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

    @Override
    protected void onDestroy() {
        tts.shutdown();
        super.onDestroy();
    }
}
