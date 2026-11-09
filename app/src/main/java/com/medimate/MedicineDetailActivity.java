package com.medimate;

import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MedicineDetailActivity extends AppCompatActivity {

    private TextView tvTitle;
    private TextView tvMedicineName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_medicine_detail);

        // XML 요소 연결
        tvTitle = findViewById(R.id.tv_title);
        tvMedicineName = findViewById(R.id.tv_medicine_name);

        ImageButton btnBack = findViewById(R.id.btn_back);

        // 이전 화면에서 전달받은 약 이름
        String medicineName = getIntent().getStringExtra("medicine_name");

        // 값이 정상적으로 전달됐을 때 화면에 표시
        if (medicineName != null) {
            tvTitle.setText(medicineName);
            tvMedicineName.setText(medicineName);
        }

        // 뒤로가기 버튼
        btnBack.setOnClickListener(v -> finish());
    }
}
