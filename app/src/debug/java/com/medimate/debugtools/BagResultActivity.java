package com.medimate.debugtools;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.medimate.net.ApiClient;
import com.medimate.net.ApiError;
import com.medimate.net.ApiException;
import com.medimate.pill.ImageCompressor;
import com.medimate.pill.model.UploadedFile;
import com.medimate.ui.Speaker;

/**
 * [시험용·디버그 전용] 팀 앱의 "분석 결과" 화면. 약봉투에서 찾은 약을 카드로 보여 주고, 카드를 누르면 "약 상세 정보"로 간다.
 * 아래의 "복용 일정 만들기"는 확정이고, 확정 뒤 "알람으로 설정할까요?"를 묻는다.
 */
public class BagResultActivity extends AppCompatActivity {

    static final String EXTRA_JOB = "jobId";
    static final String EXTRA_ITEM = "itemId";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final BagTestApi api = BagTestApi.create();
    private Speaker speaker;
    private LinearLayout list;
    private TextView status;
    private ProgressBar progress;
    private String jobId;
    private boolean spokenSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        speaker = new Speaker(this);
        LinearLayout body = Ui.page(this, "분석 결과");
        progress = new ProgressBar(this);
        body.addView(progress);
        status = Ui.text(this, "약봉투를 분석하고 있어요.", 18, Color.BLACK, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(status);
        list = Ui.column(this);
        body.addView(list);

        jobId = savedInstanceState == null ? null : savedInstanceState.getString(EXTRA_JOB);
        if (jobId == null && getIntent().getData() != null) {
            recognize(getIntent().getData());
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(EXTRA_JOB, jobId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (jobId != null) {
            refresh(); // 상세 화면에서 확인·수정하고 돌아왔을 때
        }
    }

    @Override
    protected void onDestroy() {
        speaker.shutdown();
        io.shutdownNow();
        super.onDestroy();
    }

    private void say(String text) {
        if (text != null) {
            speaker.say(status, text);
        }
    }

    // ── 인식 ──────────────────────────────────────────────────────

    private void recognize(Uri uri) {
        io.execute(() -> {
            try {
                byte[] jpeg = ImageCompressor.toUploadJpeg(getContentResolver(), uri);
                UploadedFile file = ApiClient.execute(ApiClient.get().uploadImage(
                        ApiClient.jpegPart("file", "bag.jpg", jpeg), ApiClient.textPart("MEDICATION_BAG")));
                JsonObject req = new JsonObject();
                req.addProperty("fileId", file.fileId);
                JsonObject accepted = ApiClient.execute(api.recognize(req));
                jobId = accepted.get("jobId").getAsString();
                runOnUiThread(() -> say("약봉투를 분석하고 있어요. 잠시만 기다려 주세요."));
                JsonObject job = null;
                for (int i = 0; i < 60; i++) {
                    job = ApiClient.execute(api.job(jobId));
                    if (!"PROCESSING".equals(Ui.str(job, "status"))) {
                        break;
                    }
                    Thread.sleep(2000);
                }
                JsonObject done = job;
                runOnUiThread(() -> show(done));
            } catch (ApiException e) {
                runOnUiThread(() -> fail(e.error().ttsMessage != null ? e.error().ttsMessage : e.getMessage()));
            } catch (Exception e) {
                runOnUiThread(() -> fail("약봉투를 분석하지 못했습니다. " + e.getMessage()));
            }
        });
    }

    private void refresh() {
        io.execute(() -> {
            try {
                JsonObject job = ApiClient.execute(api.job(jobId));
                runOnUiThread(() -> show(job));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("오류: " + e.getMessage()));
            }
        });
    }

    private void fail(String message) {
        progress.setVisibility(View.GONE);
        say(message);
        list.removeAllViews();
        list.addView(Ui.button(this, "다시 찍기", v -> finish()));
    }

    // ── 결과 목록 ──────────────────────────────────────────────────

    private void show(JsonObject job) {
        String st = Ui.str(job, "status");
        if ("PROCESSING".equals(st)) {
            status.setText("아직 분석 중입니다.");
            return;
        }
        progress.setVisibility(View.GONE);
        list.removeAllViews();
        if ("FAILED".equals(st)) {
            fail(Ui.str(job, "summaryTtsScript"));
            return;
        }
        boolean confirmed = "CONFIRMED".equals(st);
        int count = 0;
        for (JsonElement e : Ui.arr(job, "items")) {
            if (!Ui.bool(e.getAsJsonObject(), "excluded")) {
                drugCard(e.getAsJsonObject());
                count++;
            }
        }
        String headline = confirmed ? "복용 일정을 만들었어요." : "약 " + count + "가지를 찾았어요.";
        status.setText(headline);
        status.setTextSize(20);
        // 처음 한 번만 짧게 읽어 준다. 상세 화면에서 돌아올 때는 읽지 않는다
        if (!spokenSummary) {
            spokenSummary = true;
            say("약 " + count + "가지를 찾았어요. 약을 누르면 자세한 설명을 들을 수 있어요.");
            status.setText(headline);
        }
        if (Ui.arr(job, "generalCautions").size() > 0) {
            LinearLayout c = Ui.card(this, list, null);
            c.addView(Ui.text(this, "모든 약 주의사항", 17, Ui.WARN, true));
            c.addView(Ui.text(this, Ui.join(Ui.arr(job, "generalCautions"), "\n"), 16, Color.BLACK, false));
        }
        if (!confirmed) {
            list.addView(Ui.text(this, "", 6, Color.BLACK, false));
            list.addView(Ui.button(this, "복용 일정 만들기", v -> confirm()));
        }
    }

    /** 팀 앱의 약 카드: 약 이름이 크게, 그 아래 복용법 한 줄. */
    private void drugCard(JsonObject it) {
        boolean excluded = Ui.bool(it, "excluded");
        String itemId = Ui.str(it, "itemId");
        LinearLayout c = Ui.card(this, list, v -> startActivity(new Intent(this, BagDetailActivity.class)
                .putExtra(EXTRA_JOB, jobId).putExtra(EXTRA_ITEM, itemId)));
        String name = Ui.drugName(it);
        c.addView(Ui.text(this, "💊  " + name, 21, excluded ? Color.GRAY : Color.BLACK, true));
        String summary = Ui.str(it, "displayText");
        if (summary != null && !summary.isEmpty()) {
            c.addView(Ui.text(this, summary, 17, excluded ? Color.GRAY : Color.DKGRAY, false));
        }
        c.setContentDescription(name + ". " + (summary == null ? "" : summary) + ". 눌러서 자세한 설명 보기");
    }

    // ── 확정과 알람 ────────────────────────────────────────────────

    private void confirm() {
        io.execute(() -> {
            try {
                JsonObject req = new JsonObject();
                req.addProperty("acceptAll", true); // 약마다 확인하지 않는다. 읽지 못한 약과 이미 먹는 약은 서버가 뺀다
                // 서버는 일정을 모른다. 기기에 저장된 "복용 중" 약을 보내야 이미 먹는 약을 뺄 수 있다
                req.add("currentMedications", ApiClient.GSON.toJsonTree(LocalPlans.taking(this)));
                JsonObject result = ApiClient.execute(api.confirm(jobId, req));
                runOnUiThread(() -> askAlarm(result));
            } catch (ApiException e) {
                StringBuilder detail = new StringBuilder(e.error().ttsMessage == null ? "" : e.error().ttsMessage);
                if (e.error().details != null) {
                    for (ApiError.FieldReason r : e.error().details) {
                        detail.append("\n· ").append(r.reason);
                    }
                }
                runOnUiThread(() -> {
                    say(e.error().ttsMessage);
                    new AlertDialog.Builder(this).setTitle("일정을 만들 수 없어요").setMessage(detail.toString())
                            .setPositiveButton("확인", null).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> say("오류: " + e.getMessage()));
            }
        });
    }

    private void askAlarm(JsonObject result) {
        say(Ui.str(result, "ttsScript"));
        refresh();
        new AlertDialog.Builder(this).setTitle(Ui.str(result, "alarmQuestion"))
                .setMessage(Ui.str(result, "ttsScript"))
                .setCancelable(false)
                .setPositiveButton("예", (d, w) -> save(result, true))
                .setNegativeButton("아니요", (d, w) -> save(result, false))
                .show();
    }

    /**
     * 서버는 일정 초안(plans)만 준다. 시험 화면은 기기에 저장한다(실제 앱은 팀원의 Firestore 저장소).
     * 예: 알람도 만든다. 아니요: 알람을 끈 채로 일정만 저장한다.
     */
    private void save(JsonObject result, boolean on) {
        TestAlarms.ensureChannel(this);
        int count = 0;
        List<JsonObject> saved = LocalPlans.addConfirmed(this, result, on);
        if (on) {
            for (JsonObject plan : saved) {
                count += TestAlarms.schedule(this, plan);
            }
        }
        say(on ? "알람 " + count + "개를 설정했습니다." : "알람을 설정하지 않았습니다. 일정은 저장되어 있습니다.");
    }
}
