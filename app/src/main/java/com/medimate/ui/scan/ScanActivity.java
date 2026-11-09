package com.medimate.ui.scan;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.medimate.BuildConfig;
import com.medimate.R;
import com.medimate.tts.TtsManager;
import com.medimate.ui.drug.DrugListActivity;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 촬영·갤러리 → OCR → 약 목록 화면 (README 4-4). 자르기(UCrop)는 2차. */
public class ScanActivity extends AppCompatActivity {

    private static final String TAG = "ScanActivity";
    private static final String MSG_CAMERA_DENIED = "카메라 권한이 필요해요. 설정에서 허용해 주세요.";
    private static final String MSG_LOAD_FAILED = "사진을 불러오지 못했어요. 다시 시도해 주세요.";

    private ScanViewModel viewModel;
    private TtsManager tts;
    // 사진 디코딩은 메인 스레드에서 하지 않는다
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();

    private Button buttonCamera;
    private Button buttonGallery;
    private ProgressBar progress;
    private TextView textResult;

    private ActivityResultLauncher<String> requestCamera;
    private ActivityResultLauncher<Uri> takePicture;
    private ActivityResultLauncher<PickVisualMediaRequest> pickImage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_scan);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scan_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        buttonCamera = findViewById(R.id.button_camera);
        buttonGallery = findViewById(R.id.button_gallery);
        progress = findViewById(R.id.progress);
        textResult = findViewById(R.id.text_result);

        tts = new TtsManager(this);
        viewModel = new ViewModelProvider(this).get(ScanViewModel.class);

        requestCamera = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                launchCamera();
            } else {
                tts.speak(MSG_CAMERA_DENIED);
                textResult.setText(MSG_CAMERA_DENIED);
            }
        });
        takePicture = registerForActivityResult(new ActivityResultContracts.TakePicture(), saved -> {
            if (saved) {
                scan(photoUri());
            }
        });
        pickImage = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) {
                scan(uri);
            }
        });

        buttonCamera.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                launchCamera();
            } else {
                requestCamera.launch(Manifest.permission.CAMERA);
            }
        });
        buttonGallery.setOnClickListener(v -> pickImage.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build()));

        viewModel.getIsLoading().observe(this, loading -> {
            progress.setVisibility(loading ? View.VISIBLE : View.GONE);
            buttonCamera.setEnabled(!loading);
            buttonGallery.setEnabled(!loading);
        });
        viewModel.getStatus().observe(this, message -> {
            textResult.setText(message);
            tts.speak(message);
        });
        viewModel.getDrugs().observe(this, drugs -> {
            if (drugs == null) {
                return;
            }
            viewModel.consumeDrugs();
            textResult.setText(R.string.scan_hint);
            startActivity(DrugListActivity.newIntent(this, drugs));
        });
        viewModel.getError().observe(this, message -> {
            textResult.setText(message);
            tts.speak(message);
        });
    }

    private void launchCamera() {
        takePicture.launch(photoUri());
    }

    // 사진은 앱 캐시에만 둔다 (약봉투에 환자 정보가 있으므로 갤러리에 남기지 않는다)
    private Uri photoUri() {
        File dir = new File(getCacheDir(), "images");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".fileprovider",
                new File(dir, "scan.jpg"));
    }

    private void scan(Uri uri) {
        textResult.setText(R.string.scan_reading);
        decoder.execute(() -> {
            Bitmap bitmap;
            try {
                bitmap = UriBitmapLoader.load(getContentResolver(), uri);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "사진 디코딩 실패", e);
                runOnUiThread(() -> {
                    textResult.setText(MSG_LOAD_FAILED);
                    tts.speak(MSG_LOAD_FAILED);
                });
                return;
            }
            runOnUiThread(() -> viewModel.start(bitmap));
        });
    }

    @Override
    protected void onDestroy() {
        tts.shutdown();
        decoder.shutdown();
        super.onDestroy();
    }
}
