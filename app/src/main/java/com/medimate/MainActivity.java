package com.medimate;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.medimate.tts.TtsManager;
import com.medimate.ui.ProfileActivity;
import com.medimate.ui.drug.MyDrugsActivity;
import com.medimate.ui.recommend.RecommendChatActivity;
import com.medimate.ui.scan.ScanActivity;
import com.medimate.ui.schedule.MedicationScheduleActivity;

/** 메인: 큰 버튼 다섯 개 (약 찍기 / 복약 스케줄 / 내 약 / 영양제 추천 / 내 정보) (README 7-1). */
public class MainActivity extends AppCompatActivity {

    private TtsManager tts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        tts = new TtsManager(this);
        findViewById(R.id.button_scan).setOnClickListener(v ->
                startActivity(new Intent(this, ScanActivity.class)));
        findViewById(R.id.button_schedule).setOnClickListener(v ->
                startActivity(MedicationScheduleActivity.newIntent(this, null)));
        findViewById(R.id.button_my_drugs).setOnClickListener(v ->
                startActivity(new Intent(this, MyDrugsActivity.class)));
        findViewById(R.id.button_recommend).setOnClickListener(v ->
                startActivity(new Intent(this, RecommendChatActivity.class)));
        findViewById(R.id.button_profile).setOnClickListener(v ->
                startActivity(new Intent(this, ProfileActivity.class)));
    }

    @Override
    protected void onDestroy() {
        tts.shutdown();
        super.onDestroy();
    }
}
