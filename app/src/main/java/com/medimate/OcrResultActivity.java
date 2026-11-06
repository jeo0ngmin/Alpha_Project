package com.medimate;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class OcrResultActivity extends AppCompatActivity {

    private RecyclerView recyclerMedicine;
    private TextView tvTotalCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_ocr_result);

        recyclerMedicine = findViewById(R.id.recycler_medicine);
        tvTotalCount = findViewById(R.id.tv_total_count);

        ImageButton btnBack = findViewById(R.id.btn_back);

        // 테스트용 약 데이터
        List<Medicine> medicineList = new ArrayList<>();

        medicineList.add(new Medicine("엘도비캡슐", "1회 1정"));
        medicineList.add(new Medicine("타리민정", "1회 1정"));
        medicineList.add(new Medicine("코대원에스시럽", "1회 10mL"));
        medicineList.add(new Medicine("레포스정", "1회 1정"));
        medicineList.add(new Medicine("엑사리드캡슐", "1회 1정"));

        // 총 약 개수 표시
        tvTotalCount.setText("총 " + medicineList.size() + "개");

        // RecyclerView 세로 목록 설정
        recyclerMedicine.setLayoutManager(
                new LinearLayoutManager(this)
        );

        // Adapter 연결
        MedicineAdapter adapter = new MedicineAdapter(
                medicineList,
                medicine -> {

                    // 약 상세 화면으로 이동
                    Intent intent = new Intent(
                            OcrResultActivity.this,
                            MedicineDetailActivity.class
                    );

                    // 선택한 약 이름 전달
                    intent.putExtra(
                            "medicine_name",
                            medicine.getName()
                    );

                    // 선택한 약 복용 정보 전달
                    intent.putExtra(
                            "medicine_info",
                            medicine.getInfo()
                    );

                    startActivity(intent);
                }
        );

        recyclerMedicine.setAdapter(adapter);

        // 뒤로가기 버튼
        btnBack.setOnClickListener(v -> finish());
    }
}