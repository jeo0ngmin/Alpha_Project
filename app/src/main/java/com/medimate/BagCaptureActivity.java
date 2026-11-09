package com.medimate;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;

import com.medimate.pill.ImageCompressor;
import com.medimate.tts.TtsManager;
import com.medimate.ui.scan.ScanViewModel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * [JE 화면] 약봉투 촬영 → 서버 약봉투 인식(CLOVA OCR) → 분석 결과(OcrResultActivity).
 * 원래 이름은 MainActivity였다(홈과 이름이 겹쳐서 바꿈).
 */
public class BagCaptureActivity extends AppCompatActivity {

    private static final String TAG = "BagCaptureActivity";
    private static final String MSG_CAMERA_DENIED = "카메라 권한이 필요해요. 설정에서 허용해 주세요.";
    private static final String MSG_LOAD_FAILED = "사진을 불러오지 못했어요. 다시 시도해 주세요.";
    private static final String MSG_PHOTO_READY = "사진을 넣었어요. 아래의 약 정보 분석 시작 버튼을 눌러 주세요.";

    private ScanViewModel viewModel;
    private TtsManager tts;
    // 사진 압축·디코딩은 메인 스레드에서 하지 않는다
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();

    private ImageView preview;
    private View emptyPreview;
    private TextView description;
    private View buttonCamera;
    private View buttonGallery;
    private Button buttonStart;

    /** 서버로 보낼 사진(긴 변 1600px JPEG). 사진을 고르기 전에는 null */
    private byte[] jpeg;

    private ActivityResultLauncher<String> requestCamera;
    private ActivityResultLauncher<Uri> takePicture;
    private ActivityResultLauncher<PickVisualMediaRequest> pickImage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bag_capture);

        preview = findViewById(R.id.img_medicine_bag_preview);
        emptyPreview = findViewById(R.id.layout_empty_preview);
        description = findViewById(R.id.tv_medicine_bag_description);
        buttonCamera = findViewById(R.id.btn_camera);
        buttonGallery = findViewById(R.id.btn_gallery);
        buttonStart = findViewById(R.id.btn_start_analysis);

        tts = new TtsManager(this);
        viewModel = new ViewModelProvider(this).get(ScanViewModel.class);

        requestCamera = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                takePicture.launch(photoUri());
            } else {
                say(MSG_CAMERA_DENIED);
            }
        });
        takePicture = registerForActivityResult(new ActivityResultContracts.TakePicture(), saved -> {
            if (saved) {
                load(photoUri());
            }
        });
        pickImage = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) {
                load(uri);
            }
        });

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        buttonCamera.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                takePicture.launch(photoUri());
            } else {
                requestCamera.launch(Manifest.permission.CAMERA);
            }
        });
        buttonGallery.setOnClickListener(v -> pickImage.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build()));
        buttonStart.setOnClickListener(v -> {
            if (jpeg == null) {
                say(getString(R.string.select_medicine_bag_first));
                return;
            }
            viewModel.start(jpeg);
        });

        viewModel.getIsLoading().observe(this, loading -> {
            buttonCamera.setEnabled(!loading);
            buttonGallery.setEnabled(!loading);
            buttonStart.setEnabled(!loading && jpeg != null);
        });
        viewModel.getStatus().observe(this, this::say);
        viewModel.getError().observe(this, this::say);
        viewModel.getDrugs().observe(this, drugs -> {
            if (drugs == null) {
                return;
            }
            viewModel.consumeDrugs();
            description.setText(R.string.medicine_bag_description);
            startActivity(OcrResultActivity.newIntent(this, new ArrayList<>(drugs)));
        });
    }

    // 사진은 앱 캐시에만 둔다 (약봉투에 환자 정보가 있으므로 갤러리에 남기지 않는다)
    private Uri photoUri() {
        File dir = new File(getCacheDir(), "images");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".fileprovider",
                new File(dir, "bag.jpg"));
    }

    private void load(Uri uri) {
        decoder.execute(() -> {
            byte[] bytes;
            try {
                // EXIF 회전 적용 + 긴 변 1600px로 줄인다. 미리보기도 이 사진으로 보여 준다
                bytes = ImageCompressor.toUploadJpeg(getContentResolver(), uri);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "사진 불러오기 실패", e);
                runOnUiThread(() -> say(MSG_LOAD_FAILED));
                return;
            }
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            runOnUiThread(() -> {
                jpeg = bytes;
                preview.setImageBitmap(bitmap);
                preview.setVisibility(View.VISIBLE);
                emptyPreview.setVisibility(View.GONE);
                buttonStart.setEnabled(true);
                say(MSG_PHOTO_READY);
            });
        });
    }

    /** 진행·실패 안내는 화면 설명 칸에 보여 주고 읽어 준다 */
    private void say(String message) {
        description.setText(message);
        tts.speak(message);
    }

    @Override
    protected void onDestroy() {
        tts.shutdown();
        decoder.shutdown();
        super.onDestroy();
    }
}
