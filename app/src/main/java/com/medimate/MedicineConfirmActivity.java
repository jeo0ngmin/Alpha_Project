package com.medimate;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MedicineConfirmActivity extends AppCompatActivity {

    private TextView tvSelectedCount;

    private RecyclerView recyclerConfirmMedicine;

    private final List<MedicineConfirmItem> medicineList =
            new ArrayList<>();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(
                R.layout.activity_medicine_confirm
        );


        // =========================
        // View 연결
        // =========================

        tvSelectedCount =
                findViewById(
                        R.id.tv_selected_count
                );

        recyclerConfirmMedicine =
                findViewById(
                        R.id.recycler_confirm_medicine
                );


        // =========================
        // 뒤로가기
        // =========================

        findViewById(
                R.id.btn_back
        ).setOnClickListener(v ->
                finish()
        );


        // =========================
        // 테스트 데이터
        // 나중에 실제 OCR 결과로 교체
        // =========================

        medicineList.add(
                new MedicineConfirmItem(
                        "엘도비캡슐",
                        "1회 1정",
                        true
                )
        );


        medicineList.add(
                new MedicineConfirmItem(
                        "타리민정",
                        "1회 1정",
                        true
                )
        );


        medicineList.add(
                new MedicineConfirmItem(
                        "코대원에스시럽",
                        "1회 10mL",
                        true
                )
        );


        // =========================
        // RecyclerView
        // =========================

        MedicineConfirmAdapter adapter =
                new MedicineConfirmAdapter(
                        medicineList,
                        this::updateSelectedCount
                );


        recyclerConfirmMedicine.setLayoutManager(
                new LinearLayoutManager(this)
        );

        recyclerConfirmMedicine.setAdapter(
                adapter
        );


        updateSelectedCount();


        // =========================
        // 다시 분석
        // =========================

        findViewById(
                R.id.btn_retry_analysis
        ).setOnClickListener(v -> {

            /*
             * 현재는 이전 화면으로 돌아감.
             * 나중에 약봉투 재분석 화면과 직접 연결 가능.
             */

            finish();
        });


        // =========================
        // 확인 완료
        // =========================

        findViewById(
                R.id.btn_confirm_medicine
        ).setOnClickListener(v ->
                confirmMedicines()
        );
    }


    // =========================
    // 선택 개수 표시
    // =========================

    private void updateSelectedCount() {

        int selectedCount = 0;


        for (
                MedicineConfirmItem medicine
                : medicineList
        ) {

            if (medicine.isSelected()) {

                selectedCount++;
            }
        }


        tvSelectedCount.setText(
                "인식된 약 "
                        + medicineList.size()
                        + "개 · 선택 "
                        + selectedCount
                        + "개"
        );
    }


    // =========================
    // 확인 완료
    // =========================

    private void confirmMedicines() {

        int selectedCount = 0;


        for (
                MedicineConfirmItem medicine
                : medicineList
        ) {

            if (medicine.isSelected()) {

                selectedCount++;
            }
        }


        if (selectedCount == 0) {

            Toast.makeText(
                    this,
                    "확인할 약을 한 개 이상 선택해 주세요.",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }


        /*
         * 현재 단계에서는 선택 확인까지만 구현.
         *
         * 다음 단계에서:
         * 선택된 약
         * → 내 약 저장
         * → 복약 알림 등록
         * 으로 연결.
         */

        Toast.makeText(
                this,
                "선택한 약 "
                        + selectedCount
                        + "개를 확인했습니다.",
                Toast.LENGTH_SHORT
        ).show();
    }
}