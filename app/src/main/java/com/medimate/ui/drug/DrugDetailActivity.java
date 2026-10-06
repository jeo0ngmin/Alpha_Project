package com.medimate.ui.drug;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.medimate.R;
import com.medimate.model.Drug;
import com.medimate.tts.TtsManager;

/**
 * 약 상세 + 낭독 (README 1장). 열리면 전체를 읽어 주고, 항목을 누르면 그 항목만 다시 읽는다.
 * 낭독 문장 변환("500mg" → "오백 밀리그램")은 2차.
 */
public class DrugDetailActivity extends AppCompatActivity {

    private static final String EXTRA_DRUG = "drug";
    private static final String NONE = "정보가 없어요";

    private TtsManager tts;

    public static Intent newIntent(Context context, Drug drug) {
        return new Intent(context, DrugDetailActivity.class).putExtra(EXTRA_DRUG, drug);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_drug_detail);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.drug_detail_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Drug drug = (Drug) getIntent().getSerializableExtra(EXTRA_DRUG);
        if (drug == null) {
            finish();
            return;
        }
        tts = new TtsManager(this);

        TextView name = findViewById(R.id.text_name);
        name.setText(drug.getName());
        name.setOnClickListener(v -> tts.speak(drug.getName()));

        bindSection(R.id.section_dosage, getString(R.string.detail_dosage), drug.getDosage());
        bindSection(R.id.section_storage, getString(R.string.detail_storage), drug.getStorage());
        bindSection(R.id.section_description, getString(R.string.detail_description), drug.getDescription());
        bindSection(R.id.section_warning, getString(R.string.detail_warning), drug.getWarning());

        String all = drug.getName() + ". "
                + sentence(getString(R.string.detail_dosage), drug.getDosage())
                + sentence(getString(R.string.detail_storage), drug.getStorage())
                + sentence(getString(R.string.detail_description), drug.getDescription())
                + sentence(getString(R.string.detail_warning), drug.getWarning());
        findViewById(R.id.button_speak_all).setOnClickListener(v -> tts.speak(all));

        if (savedInstanceState == null) {
            tts.speak(all);
        }
    }

    // 항목을 누르면 "복용법. 하루 세 번 식후 30분" 처럼 제목과 내용을 읽는다
    private void bindSection(int sectionId, String label, String value) {
        View section = findViewById(sectionId);
        ((TextView) section.findViewById(R.id.text_label)).setText(label);
        ((TextView) section.findViewById(R.id.text_value)).setText(value.isEmpty() ? NONE : value);
        section.setOnClickListener(v -> tts.speak(sentence(label, value)));
    }

    private static String sentence(String label, String value) {
        return label + ". " + (value.isEmpty() ? NONE : value) + ". ";
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.shutdown();
        }
        super.onDestroy();
    }
}
