package com.medimate.pill;

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.medimate.R;
import com.medimate.pill.model.ColorClass;
import com.medimate.pill.model.DrugShape;
import com.medimate.pill.model.MedicationSummary;
import com.medimate.ui.EdgeToEdgeHelper;
import com.medimate.ui.LargeDialog;
import com.medimate.ui.Speaker;

/** 사진 식별이 실패했을 때의 대체 경로: 모양·색·글자·이름으로 직접 찾는다. */
public class PillSearchActivity extends AppCompatActivity {

    private static final String EXTRA_SHAPE = "shape";
    private static final String EXTRA_COLORS = "colors";
    private static final String EXTRA_PRINT = "print";

    /** 식별 결과에서 넘어올 때 추정된 특징으로 조건을 미리 채운다. */
    public static Intent intent(Context context, DrugShape shape, ArrayList<ColorClass> colors, String printText) {
        return new Intent(context, PillSearchActivity.class)
                .putExtra(EXTRA_SHAPE, shape)
                .putExtra(EXTRA_COLORS, colors)
                .putExtra(EXTRA_PRINT, printText);
    }

    private PillSearchViewModel viewModel;
    private Speaker speaker;
    private Spinner shape;
    private ChipGroup colors;
    private TextInputEditText print;
    private TextInputEditText name;
    private TextView status;
    private MaterialButton more;
    private PillRowAdapter<MedicationSummary> adapter;
    private AlertDialog dialog;

    @Override
    @SuppressWarnings({"deprecation", "unchecked"}) // getSerializableExtra: core 1.10 전까지는 기존 API 사용
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_pill_search);
        EdgeToEdgeHelper.padForSystemBars(findViewById(R.id.root));
        speaker = new Speaker(this);

        shape = findViewById(R.id.spShape);
        colors = findViewById(R.id.cgColors);
        print = findViewById(R.id.etPrint);
        name = findViewById(R.id.etName);
        status = findViewById(R.id.tvSearchStatus);
        more = findViewById(R.id.btnMore);

        HintViews.setupShapes(shape);
        HintViews.setupColors(colors, ColorClass.values().length, null);

        adapter = new PillRowAdapter<>(this::toRow, this::showDetail);
        RecyclerView list = findViewById(R.id.rvResults);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(PillSearchViewModel.class);
        viewModel.state().observe(this, this::render);
        findViewById(R.id.btnDoSearch).setOnClickListener(v -> search());
        more.setOnClickListener(v -> viewModel.loadMore());
        name.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search();
                return true;
            }
            return false;
        });

        if (savedInstanceState == null) {
            Intent in = getIntent();
            HintViews.selectShape(shape, (DrugShape) in.getSerializableExtra(EXTRA_SHAPE));
            HintViews.selectColors(colors, (List<ColorClass>) in.getSerializableExtra(EXTRA_COLORS));
            print.setText(in.getStringExtra(EXTRA_PRINT));
        }
    }

    @Override
    protected void onDestroy() {
        if (dialog != null) {
            dialog.dismiss();
        }
        speaker.shutdown();
        super.onDestroy();
    }

    private void search() {
        viewModel.search(HintViews.selectedShape(shape), HintViews.selectedColors(colors), text(print), text(name));
    }

    private void render(PillSearchViewModel.State s) {
        adapter.submit(s.items);
        more.setVisibility(s.hasMore ? View.VISIBLE : View.GONE);
        more.setEnabled(!s.loading);
        if (s.loading) {
            status.setText(R.string.search_loading);
        } else if (s.error != null) {
            speaker.say(status, s.error.error().ttsMessage);
        } else if (s.announcedCount != null) {
            speaker.say(status, s.announcedCount == 0 ? getString(R.string.search_empty)
                    : getString(R.string.search_count, s.announcedCount));
        }
    }

    private PillRowAdapter.Row toRow(MedicationSummary m) {
        String look = describe(m);
        return new PillRowAdapter.Row(m.itemName, m.entpName, look, m.itemImageUrl, false,
                getString(R.string.search_item_desc, m.itemName, nullToEmpty(m.entpName), look));
    }

    private void showDetail(MedicationSummary m) {
        String none = getString(R.string.none);
        dialog = new LargeDialog(this, m.itemName,
                getString(R.string.search_detail, m.itemName, nullToEmpty(m.entpName),
                        m.drugShape == null ? none : m.drugShape.label,
                        m.colorClass1 == null ? none : m.colorClass1.label,
                        m.printFront == null ? none : m.printFront,
                        m.className == null ? none : m.className))
                .button(R.string.result_ok, null)
                .show();
    }

    private static String describe(MedicationSummary m) {
        StringBuilder sb = new StringBuilder();
        if (m.colorClass1 != null) {
            sb.append(m.colorClass1.label).append(' ');
        }
        if (m.drugShape != null) {
            sb.append(m.drugShape.label);
        }
        if (m.printFront != null && !m.printFront.isEmpty()) {
            sb.append(" · ").append(m.printFront);
        }
        return sb.toString().trim();
    }

    private static String text(TextInputEditText view) {
        return view.getText() == null ? null : view.getText().toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
