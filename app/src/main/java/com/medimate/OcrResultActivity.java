package com.medimate;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.medimate.model.Drug;
import com.medimate.tts.TtsManager;

import java.util.ArrayList;
import java.util.List;

/** [JE 화면] 약봉투 분석 결과: 서버가 읽은 약 목록. 누르면 약 상세 화면. */
public class OcrResultActivity extends AppCompatActivity {

    private static final String EXTRA_DRUGS = "drugs";

    private TtsManager tts;

    public static Intent newIntent(Context context, ArrayList<Drug> drugs) {
        return new Intent(context, OcrResultActivity.class).putExtra(EXTRA_DRUGS, drugs);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_ocr_result);

        RecyclerView recyclerMedicine = findViewById(R.id.recycler_medicine);
        TextView tvTotalCount = findViewById(R.id.tv_total_count);
        ImageButton btnBack = findViewById(R.id.btn_back);

        List<Drug> drugs = readDrugs();
        List<Medicine> medicineList = new ArrayList<>();
        for (Drug drug : drugs) {
            medicineList.add(new Medicine(drug.getName(), drug.getDosage()));
        }

        // 총 약 개수 표시
        tvTotalCount.setText("총 " + medicineList.size() + "개");
        tts = new TtsManager(this);
        tts.speak("약 " + medicineList.size() + "개를 찾았어요. 약을 누르면 자세한 정보를 볼 수 있어요.");

        recyclerMedicine.setLayoutManager(new LinearLayoutManager(this));
        MedicineAdapter adapter = new MedicineAdapter(medicineList, medicine -> {
            Drug drug = drugs.get(medicineList.indexOf(medicine));
            startActivity(MedicineDetailActivity.newIntent(this, drug));
        });
        recyclerMedicine.setAdapter(adapter);

        // 뒤로가기 버튼
        btnBack.setOnClickListener(v -> finish());
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    private List<Drug> readDrugs() {
        Object extra = getIntent().getSerializableExtra(EXTRA_DRUGS);
        return extra instanceof ArrayList ? (ArrayList<Drug>) extra : new ArrayList<>();
    }

    @Override
    protected void onDestroy() {
        tts.shutdown();
        super.onDestroy();
    }
}
