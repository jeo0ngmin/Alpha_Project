package com.medimate.debugtools;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.medimate.net.ApiClient;
import com.medimate.ui.Speaker;

/**
 * [시험용·디버그 전용] 팀 앱의 "약 상세 정보" 화면. 궁금한 항목(약 설명, 주의사항, 복용방법, 보관방법…)을 고르면
 * 내용을 보여 주고 읽어 준다.
 */
public class BagDetailActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final BagTestApi api = BagTestApi.create();
    private Speaker speaker;
    private LinearLayout body;
    private TextView status;
    private TextView infoDisplay;
    private String jobId;
    private String itemId;
    private String lastSpoken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        speaker = new Speaker(this);
        jobId = getIntent().getStringExtra(BagResultActivity.EXTRA_JOB);
        itemId = getIntent().getStringExtra(BagResultActivity.EXTRA_ITEM);
        body = Ui.page(this, "약 상세 정보");
        status = Ui.text(this, "불러오는 중입니다.", 16, Color.DKGRAY, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(status);
        load();
    }

    @Override
    protected void onDestroy() {
        speaker.shutdown();
        io.shutdownNow();
        super.onDestroy();
    }

    private void load() {
        io.execute(() -> {
            try {
                JsonObject job = ApiClient.execute(api.job(jobId));
                JsonObject found = null;
                for (JsonElement e : Ui.arr(job, "items")) {
                    if (itemId.equals(Ui.str(e.getAsJsonObject(), "itemId"))) {
                        found = e.getAsJsonObject();
                    }
                }
                JsonObject it = found;
                boolean confirmed = "CONFIRMED".equals(Ui.str(job, "status"));
                runOnUiThread(() -> {
                    if (it == null) {
                        status.setText("약을 찾을 수 없습니다.");
                    } else {
                        show(it, confirmed);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("오류: " + e.getMessage()));
            }
        });
    }

    private void say(String text) {
        if (text != null) {
            lastSpoken = text;
            speaker.say(status, text);
        }
    }

    private void show(JsonObject it, boolean confirmed) {
        body.removeAllViews();
        body.addView(status);
        String name = Ui.drugName(it);
        LinearLayout head = Ui.card(this, body, null);
        head.addView(Ui.text(this, "💊  " + name, 22, Color.BLACK, true));
        if (Ui.str(it, "displayText") != null) {
            head.addView(Ui.text(this, Ui.str(it, "displayText"), 18, Color.BLACK, false));
        }
        JsonArray bagCautions = Ui.arr(it, "cautions");
        if (bagCautions.size() > 0) {
            head.addView(Ui.text(this, "약봉투 주의: " + Ui.join(bagCautions, " / "), 16, Ui.WARN, false));
        }

        // 궁금한 항목 버튼 (팀 앱과 같은 구성)
        LinearLayout menu = Ui.card(this, body, null);
        menu.addView(Ui.text(this, "궁금한 항목을 선택하세요", 18, Color.BLACK, true));
        JsonArray sections = Ui.arr(it, "sections");
        for (JsonElement e : sections) {
            JsonObject s = e.getAsJsonObject();
            MaterialButton b = Ui.outlined(this, Ui.str(s, "title"), v -> showSection(s));
            menu.addView(b);
        }
        LinearLayout info = Ui.card(this, body, null);
        infoDisplay = Ui.text(this, "버튼을 눌러 약의 상세 정보를 확인하세요.", 18, Color.BLACK, false);
        info.addView(infoDisplay);
        info.addView(Ui.outlined(this, "🔊  다시 듣기", v -> {
            if (lastSpoken != null) {
                speaker.say(status, lastSpoken);
            }
        }));

        say(name + ". 궁금한 항목을 골라 주세요."); // 읽어 주기만 하고 화면 맨 위에는 글을 남기지 않는다
        status.setVisibility(View.GONE);
    }

    private void showSection(JsonObject s) {
        String text = Ui.str(s, "text");
        infoDisplay.setText(Ui.str(s, "title") + "\n\n" + (text != null ? text : Ui.str(s, "ttsText")));
        say(Ui.str(s, "ttsText"));
    }
}
