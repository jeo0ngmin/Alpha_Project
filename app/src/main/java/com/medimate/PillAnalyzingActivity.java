package com.medimate;

import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

public class PillAnalyzingActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_URI = "pill_image_uri";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pill_analyzing);

        ImageButton btnBack = findViewById(R.id.btn_back);

        ImageView imgAnalysisTarget =
                findViewById(R.id.img_analysis_target);

        MaterialButton btnCancelAnalysis =
                findViewById(R.id.btn_cancel_analysis);

        String imageUriText =
                getIntent().getStringExtra(EXTRA_IMAGE_URI);

        if (imageUriText == null || imageUriText.isEmpty()) {
            Toast.makeText(
                    this,
                    R.string.select_image_first,
                    Toast.LENGTH_SHORT
            ).show();

            finish();
            return;
        }

        Uri imageUri = Uri.parse(imageUriText);

        imgAnalysisTarget.setImageURI(imageUri);

        btnBack.setOnClickListener(v -> finish());
        btnCancelAnalysis.setOnClickListener(v -> finish());

        startPillAnalysis(imageUri);
    }

    /*
     * 다음 단계에서 실제 알약 분석 API를 연결할 위치
     */
    private void startPillAnalysis(Uri imageUri) {
        /*
         * TODO:
         * 1. 이미지 파일을 서버 또는 AI API에 업로드
         * 2. 분석 응답 수신
         * 3. 분석 결과 화면으로 이동
         */
    }
}