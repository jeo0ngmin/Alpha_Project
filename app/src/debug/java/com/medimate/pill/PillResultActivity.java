package com.medimate.pill;

import java.io.File;
import java.util.ArrayList;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.medimate.debugtools.DebugHomeActivity;
import com.medimate.R;
import com.medimate.debugtools.LocalPlans;
import com.medimate.net.ApiClient;
import com.medimate.pill.model.ColorClass;
import com.medimate.pill.model.FallbackAction;
import com.medimate.pill.model.PillCandidate;
import com.medimate.pill.model.PillFeatures;
import com.medimate.pill.model.PillHints;
import com.medimate.pill.model.PillIdentificationJob;
import com.medimate.pill.model.PillJobStatus;
import com.medimate.pill.model.SelectResult;
import com.medimate.ui.EdgeToEdgeHelper;
import com.medimate.ui.LargeDialog;
import com.medimate.ui.Speaker;

/** 업로드·식별 진행 상황과 후보 목록. 후보를 누르면 확인 후 선택한다. */
public class PillResultActivity extends AppCompatActivity {

    private static final String EXTRA_FRONT = "front";
    private static final String EXTRA_BACK = "back";
    private static final String EXTRA_HINTS = "hints";

    public static Intent intent(Context context, File front, File back, PillHints hints) {
        return new Intent(context, PillResultActivity.class)
                .putExtra(EXTRA_FRONT, front.getPath())
                .putExtra(EXTRA_BACK, back == null ? null : back.getPath())
                .putExtra(EXTRA_HINTS, hints);
    }

    private PillResultViewModel viewModel;
    private Speaker speaker;
    private View progress;
    private TextView status;
    private TextView features;
    private MaterialButton retake;
    private MaterialButton search;
    private MaterialButton retry;
    private PillRowAdapter<PillCandidate> adapter;
    private AlertDialog dialog;
    private PillIdentificationJob lastJob;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_pill_result);
        EdgeToEdgeHelper.padForSystemBars(findViewById(R.id.root));
        speaker = new Speaker(this);

        progress = findViewById(R.id.progress);
        status = findViewById(R.id.tvStatus);
        features = findViewById(R.id.tvFeatures);
        retake = findViewById(R.id.btnRetake);
        search = findViewById(R.id.btnSearch);
        retry = findViewById(R.id.btnRetry);

        adapter = new PillRowAdapter<>(this::toRow, this::confirm);
        RecyclerView list = findViewById(R.id.rvCandidates);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        retake.setOnClickListener(v -> finish()); // 촬영 화면은 사진을 그대로 들고 있다
        search.setOnClickListener(v -> openSearch());
        retry.setOnClickListener(v -> {
            finish();
            startActivity(getIntent());
        });

        Intent in = getIntent();
        String back = in.getStringExtra(EXTRA_BACK);
        @SuppressWarnings("deprecation") // IntentCompat.getSerializableExtra는 core 1.10부터라 기존 API를 쓴다
        PillHints hints = (PillHints) in.getSerializableExtra(EXTRA_HINTS);
        viewModel = new ViewModelProvider(this).get(PillResultViewModel.class);
        viewModel.state().observe(this, this::render);
        viewModel.start(new File(in.getStringExtra(EXTRA_FRONT)), back == null ? null : new File(back), hints,
                LocalPlans.taking(this));
    }

    @Override
    protected void onDestroy() {
        if (dialog != null) {
            dialog.dismiss();
        }
        speaker.shutdown();
        super.onDestroy();
    }

    private void render(PillResultViewModel.State s) {
        boolean busy = s.phase == PillResultViewModel.Phase.UPLOADING
                || s.phase == PillResultViewModel.Phase.IDENTIFYING
                || s.phase == PillResultViewModel.Phase.SELECTING;
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        say(s.message);

        PillIdentificationJob job = s.job;
        if (job != null && job != lastJob) {
            lastJob = job;
            PillFeatures f = job.extractedFeatures;
            String described = f == null ? "" : f.describe();
            features.setVisibility(described.isEmpty() ? View.GONE : View.VISIBLE);
            features.setText(getString(R.string.result_features, described));
            adapter.submit(job.candidatesOrEmpty());
        }

        boolean error = s.phase == PillResultViewModel.Phase.ERROR;
        boolean noCandidates = job != null && job.candidatesOrEmpty().isEmpty();
        retry.setVisibility(error && s.error != null && s.error.isRetryable() ? View.VISIBLE : View.GONE);
        // 서버가 권하는 대체 행동을 채운 버튼으로 강조한다
        FallbackAction fallback = job == null ? null : job.fallbackAction;
        emphasize(retake, fallback == FallbackAction.RETAKE || (error && s.error != null && s.error.isRetakeNeeded()));
        emphasize(search, fallback == FallbackAction.MANUAL_SEARCH);
        if (noCandidates && job.status == PillJobStatus.COMPLETED) {
            say(getString(R.string.result_no_candidates));
        }

        if (s.phase == PillResultViewModel.Phase.SELECTED && s.selected != null) {
            if (viewModel.consumeAddRequest() && s.selected.draftPlan != null) {
                // 서버는 일정을 저장하지 않는다. 시험 화면은 기기에, 실제 앱은 Firestore에 저장한다
                LocalPlans.addDraft(this, ApiClient.GSON.toJsonTree(s.selected.draftPlan).getAsJsonObject(),
                        s.selected.medication == null ? null : s.selected.medication.itemName);
            }
            showSelected(s.selected);
        }
    }

    private void say(String text) {
        if (text != null && !text.equals(viewModel.lastSpoken)) {
            viewModel.lastSpoken = text;
            speaker.say(status, text);
        } else if (text != null) {
            status.setText(text); // 화면 회전 후에는 다시 읽지 않고 표시만
        }
    }

    /** 외곽선 버튼을 채운 버튼처럼 보이게 하거나 되돌린다. */
    private void emphasize(MaterialButton button, boolean on) {
        int primary = themeColor(androidx.appcompat.R.attr.colorPrimary);
        int onPrimary = themeColor(com.google.android.material.R.attr.colorOnPrimary);
        button.setBackgroundTintList(ColorStateList.valueOf(on ? primary : Color.TRANSPARENT));
        button.setTextColor(on ? onPrimary : primary);
    }

    private int themeColor(int attr) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    private PillRowAdapter.Row toRow(PillCandidate c) {
        String mine = c.isInMyMedications ? getString(R.string.candidate_desc_mine) : "";
        String desc = getString(R.string.candidate_desc, c.rank, c.itemName, nullToEmpty(c.entpName),
                c.matchPercent(), mine);
        return new PillRowAdapter.Row(c.itemName, c.entpName, getString(R.string.candidate_score, c.matchPercent()),
                c.itemImageUrl, c.isInMyMedications, desc);
    }

    private void confirm(PillCandidate c) {
        dialog = new LargeDialog(this, getString(R.string.result_confirm_title),
                getString(R.string.result_confirm_message, c.itemName, nullToEmpty(c.entpName)))
                .button(R.string.result_confirm_add, () -> viewModel.select(c.itemSeq, true))
                .button(R.string.result_confirm_only, () -> viewModel.select(c.itemSeq, false))
                .button(R.string.result_confirm_no, null)
                .show();
    }

    private void showSelected(SelectResult result) {
        if (dialog != null && dialog.isShowing()) {
            return;
        }
        String name = result.medication == null ? "" : result.medication.itemName;
        String plan = Labels.draftPlan(result.draftPlan);
        String message = getString(R.string.result_selected_message, name, plan);
        say(name + Labels.objectParticle(name) + " 선택했습니다. 복용 계획 초안은 " + plan + "입니다.");
        dialog = new LargeDialog(this, getString(R.string.result_selected_title), message)
                .cancelable(false)
                .button(R.string.result_ok, () -> {
                    Intent home = new Intent(this, DebugHomeActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(home);
                    finish();
                })
                .show();
    }

    private void openSearch() {
        PillFeatures f = lastJob == null ? null : lastJob.extractedFeatures;
        ArrayList<ColorClass> colors = new ArrayList<>();
        if (f != null && f.colorClass1 != null) {
            colors.add(f.colorClass1);
        }
        if (f != null && f.colorClass2 != null) {
            colors.add(f.colorClass2);
        }
        startActivity(PillSearchActivity.intent(this, f == null ? null : f.drugShape, colors,
                f == null ? null : f.printFront));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
