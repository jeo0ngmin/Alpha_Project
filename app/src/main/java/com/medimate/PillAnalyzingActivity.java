package com.medimate;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.medimate.model.Drug;
import com.medimate.pill.ImageCompressor;
import com.medimate.tts.TtsManager;
import com.medimate.ui.EdgeToEdgeHelper;
import com.medimate.ui.pill.PillViewModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * [JE 화면] 알약 분석 중. 사진을 서버 알약 식별로 보내고, 끝나면
 * 확신하면 약 상세, 아니면 비슷한 후보 목록(OcrResultActivity)을 연다.
 */
public class PillAnalyzingActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_URI = "pill_image_uri";

    private static final String TAG = "PillAnalyzingActivity";
    private static final String MSG_LOAD_FAILED = "사진을 불러오지 못했어요. 다시 찍어 주세요.";

    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private TtsManager tts;
    private PillViewModel viewModel;
    private TextView headline;
    private TextView description;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pill_analyzing);
        EdgeToEdgeHelper.padForSystemBars(findViewById(R.id.pill_analyzing_root));

        ImageButton btnBack = findViewById(R.id.btn_back);
        ImageView imgAnalysisTarget = findViewById(R.id.img_analysis_target);
        MaterialButton btnCancelAnalysis = findViewById(R.id.btn_cancel_analysis);
        headline = findViewById(R.id.tv_analysis_headline);
        description = findViewById(R.id.tv_analysis_description);
        tts = new TtsManager(this);

        String imageUriText = getIntent().getStringExtra(EXTRA_IMAGE_URI);
        if (imageUriText == null || imageUriText.isEmpty()) {
            finish();
            return;
        }
        Uri imageUri = Uri.parse(imageUriText);
        imgAnalysisTarget.setImageURI(imageUri);

        // 취소하면 화면을 닫고, ViewModel이 정리되며 서버 조회도 멈춘다
        btnBack.setOnClickListener(v -> finish());
        btnCancelAnalysis.setOnClickListener(v -> finish());

        viewModel = new ViewModelProvider(this).get(PillViewModel.class);
        viewModel.getStatus().observe(this, this::say);
        viewModel.getError().observe(this, message -> {
            if (message == null) {
                return;
            }
            headline.setText(R.string.pill_analyzing_failed);
            btnCancelAnalysis.setText(R.string.pill_analyzing_retake);
            say(message);
        });
        viewModel.getResult().observe(this, result -> {
            if (result == null) {
                return;
            }
            openResult(result);
        });

        if (!viewModel.isStarted()) {
            start(imageUri);
        }
    }

    private void start(Uri imageUri) {
        decoder.execute(() -> {
            byte[] jpeg;
            try {
                // EXIF 회전 적용 + 긴 변 1600px. 사진 내용은 로그에 남기지 않는다
                jpeg = ImageCompressor.toUploadJpeg(getContentResolver(), imageUri);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "사진 불러오기 실패", e);
                runOnUiThread(() -> viewModel.failLoad(MSG_LOAD_FAILED));
                return;
            }
            runOnUiThread(() -> viewModel.start(jpeg));
        });
    }

    private void openResult(PillViewModel.Result result) {
        if (result.drugs.size() == 1) {
            Drug drug = result.drugs.get(0);
            // 이 화면은 곧 닫히므로 안내는 상세 화면이 읽는다
            startActivity(MedicineDetailActivity.newIntent(this, drug)
                    .putExtra(MedicineDetailActivity.EXTRA_ANNOUNCE,
                            result.spoken != null ? result.spoken : getString(R.string.pill_result_one, drug.getName())));
        } else {
            startActivity(OcrResultActivity.candidatesIntent(this, new ArrayList<>(result.drugs), result.notes));
        }
        finish();
    }

    private void say(String message) {
        description.setText(message);
        tts.speak(message);
    }

    @Override
    protected void onDestroy() {
        tts.shutdown();
        decoder.shutdownNow();
        super.onDestroy();
    }
}
