package com.medimate;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.medimate.model.Drug;
import com.medimate.tts.TtsManager;

/**
 * [JE 화면] 약 상세 정보. 카드(약 설명·복용방법·보관방법·주의사항)를 누르면 내용을 보여 주고 읽어 준다.
 * 내용은 서버 약봉투 인식 결과(봉투 글자 + e약은요)이고, 앱이 만들어 내지 않는다.
 */
public class MedicineDetailActivity extends AppCompatActivity {

    private static final String EXTRA_DRUG = "drug";
    private static final String MSG_EMPTY = "봉투와 공식 자료에서 찾지 못했어요. 약사에게 확인해 주세요.";
    private static final String MSG_NO_INGREDIENT = "성분 정보는 아직 제공하지 않아요. 약사에게 확인해 주세요.";

    private TtsManager tts;

    public static Intent newIntent(Context context, Drug drug) {
        return new Intent(context, MedicineDetailActivity.class).putExtra(EXTRA_DRUG, drug);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_medicine_detail);

        TextView tvTitle = findViewById(R.id.tv_title);
        TextView tvMedicineName = findViewById(R.id.tv_medicine_name);
        ImageButton btnBack = findViewById(R.id.btn_back);
        tts = new TtsManager(this);

        @SuppressWarnings("deprecation")
        Drug drug = (Drug) getIntent().getSerializableExtra(EXTRA_DRUG);
        if (drug == null) {
            finish();
            return;
        }
        tvTitle.setText(drug.getName());
        tvMedicineName.setText(drug.getName());

        findViewById(R.id.card_ingredient).setOnClickListener(v -> show("약 성분", MSG_NO_INGREDIENT));
        findViewById(R.id.card_description).setOnClickListener(v -> show("약 설명", drug.getDescription()));
        findViewById(R.id.card_dosage).setOnClickListener(v -> show("복용방법", drug.getDosage()));
        findViewById(R.id.card_storage).setOnClickListener(v -> show("보관방법", drug.getStorage()));
        findViewById(R.id.card_warning).setOnClickListener(v -> show("주의사항", drug.getWarning()));

        btnBack.setOnClickListener(v -> finish());
    }

    private void show(String title, String text) {
        String body = text == null || text.trim().isEmpty() ? MSG_EMPTY : text.trim();
        tts.speak(title + "입니다. " + body);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("닫기", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.shutdown();
        }
        super.onDestroy();
    }
}
