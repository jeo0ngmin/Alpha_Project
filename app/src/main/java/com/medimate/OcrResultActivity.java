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
import com.medimate.ui.EdgeToEdgeHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * [JE 화면] 분석 결과 약 목록. 누르면 약 상세 화면.
 * 약봉투: 서버가 읽은 약들.
 * 알약: 서버가 확신하지 못했을 때의 비슷한 후보들(candidatesIntent).
 */
public class OcrResultActivity extends AppCompatActivity {

    private static final String EXTRA_DRUGS = "drugs";

    /** 알약 후보일 때 항목별 한 줄 안내(업체명). 약봉투면 없다 */
    private static final String EXTRA_NOTES = "notes";

    private TtsManager tts;


    public static Intent newIntent(
            Context context,
            ArrayList<Drug> drugs
    ) {

        return new Intent(
                context,
                OcrResultActivity.class
        ).putExtra(
                EXTRA_DRUGS,
                drugs
        );
    }


    static Intent candidatesIntent(
            Context context,
            ArrayList<Drug> drugs,
            ArrayList<String> notes
    ) {

        return newIntent(
                context,
                drugs
        ).putStringArrayListExtra(
                EXTRA_NOTES,
                notes
        );
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(
                R.layout.activity_ocr_result
        );

        EdgeToEdgeHelper.padForSystemBars(
                findViewById(
                        R.id.ocr_result_root
                )
        );


        // =========================
        // View 연결
        // =========================

        RecyclerView recyclerMedicine =
                findViewById(
                        R.id.recycler_medicine
                );

        TextView tvTotalCount =
                findViewById(
                        R.id.tv_total_count
                );

        TextView tvMedicineLabel =
                findViewById(
                        R.id.tv_medicine_label
                );

        TextView tvResultDescription =
                findViewById(
                        R.id.tv_result_description
                );

        TextView tvListTitle =
                findViewById(
                        R.id.tv_list_title
                );

        ImageButton btnBack =
                findViewById(
                        R.id.btn_back
                );


        tts =
                new TtsManager(this);


        // =========================
        // 결과 데이터
        // =========================

        List<Drug> drugs =
                readDrugs();

        ArrayList<String> notes =
                getIntent()
                        .getStringArrayListExtra(
                                EXTRA_NOTES
                        );


        /*
         * notes가 있으면
         * 알약을 하나로 확정하지 못한 후보 화면이다.
         */
        boolean candidates =
                notes != null;


        List<Medicine> medicineList =
                new ArrayList<>();


        for (
                int i = 0;
                i < drugs.size();
                i++
        ) {

            Drug drug =
                    drugs.get(i);

            String info =
                    candidates
                            && i < notes.size()
                            ? notes.get(i)
                            : drug.getDosage();

            medicineList.add(
                    new Medicine(
                            drug.getName(),
                            info
                    )
            );
        }


        // =========================
        // 개수 표시
        // =========================

        tvTotalCount.setText(
                "총 "
                        + medicineList.size()
                        + "개"
        );


        // =========================
        // 알약 후보 결과
        // =========================

        if (candidates) {

            /*
             * 확정된 약이 아니므로
             * "인식된 약"이라는 표현을 사용하지 않는다.
             */
            tvMedicineLabel.setText(
                    "비슷한 약 후보"
            );

            tvListTitle.setText(
                    "후보 목록"
            );

            tvResultDescription.setText(
                    R.string.pill_candidates_description
            );


            if (savedInstanceState == null) {

                tts.speak(
                        getString(
                                R.string.pill_candidates_spoken,
                                medicineList.size()
                        )
                );
            }

        } else {

            // =========================
            // 약봉투 분석 결과
            // =========================

            tvMedicineLabel.setText(
                    "인식된 약"
            );

            tvListTitle.setText(
                    "약 목록"
            );


            if (savedInstanceState == null) {

                tts.speak(
                        "약 "
                                + medicineList.size()
                                + "개를 찾았어요. "
                                + "약을 누르면 자세한 정보를 볼 수 있어요."
                );
            }
        }


        // =========================
        // 약 목록
        // =========================

        recyclerMedicine.setLayoutManager(
                new LinearLayoutManager(this)
        );


        MedicineAdapter adapter =
                new MedicineAdapter(
                        medicineList,
                        medicine -> {

                            Drug drug =
                                    drugs.get(
                                            medicineList.indexOf(
                                                    medicine
                                            )
                                    );

                            startActivity(
                                    MedicineDetailActivity.newIntent(
                                            this,
                                            drug
                                    )
                            );
                        }
                );


        recyclerMedicine.setAdapter(
                adapter
        );


        // =========================
        // 뒤로가기
        // =========================

        btnBack.setOnClickListener(
                v -> finish()
        );
    }


    @SuppressWarnings({
            "unchecked",
            "deprecation"
    })
    private List<Drug> readDrugs() {

        Object extra =
                getIntent()
                        .getSerializableExtra(
                                EXTRA_DRUGS
                        );

        return extra instanceof ArrayList
                ? (ArrayList<Drug>) extra
                : new ArrayList<>();
    }


    @Override
    protected void onDestroy() {

        if (tts != null) {
            tts.shutdown();
        }

        super.onDestroy();
    }
}