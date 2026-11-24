package com.medimate;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MyMedicineActivity extends AppCompatActivity {

    private RecyclerView recyclerMyMedicine;
    private TextView tvMedicineCount;

    private final List<Medicine> medicineList = new ArrayList<>();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my_medicine);


        // =========================
        // View 연결
        // =========================

        recyclerMyMedicine =
                findViewById(R.id.recycler_my_medicine);

        tvMedicineCount =
                findViewById(R.id.tv_my_medicine_count);


        // =========================
        // 뒤로가기
        // =========================

        findViewById(R.id.btn_back)
                .setOnClickListener(v -> finish());


        // =========================
        // RecyclerView 설정
        // =========================

        recyclerMyMedicine.setLayoutManager(
                new LinearLayoutManager(this)
        );


        // =========================
        // 테스트용 약 데이터
        // 실제 DB 연결 후 교체 예정
        // =========================

        medicineList.add(
                new Medicine(
                        "혈압약",
                        "1회 1정 · 매일"
                )
        );

        medicineList.add(
                new Medicine(
                        "소염제",
                        "1회 1정 · 월/수/금"
                )
        );


        // =========================
        // 약 개수 표시
        // =========================

        tvMedicineCount.setText(
                medicineList.size() + "개"
        );


        // =========================
        // Adapter 연결
        // =========================

        MedicineAdapter adapter =
                new MedicineAdapter(
                        medicineList,
                        medicine -> {

                            Intent intent = new Intent(
                                    MyMedicineActivity.this,
                                    MedicineDetailActivity.class
                            );

                            intent.putExtra(
                                    "medicine_name",
                                    medicine.getName()
                            );

                            intent.putExtra(
                                    "medicine_info",
                                    medicine.getInfo()
                            );

                            startActivity(intent);
                        }
                );


        recyclerMyMedicine.setAdapter(adapter);
    }
}