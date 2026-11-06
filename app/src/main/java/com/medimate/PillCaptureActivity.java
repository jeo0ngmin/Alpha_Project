package com.medimate;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.IOException;

public class PillCaptureActivity extends AppCompatActivity {

    private static final String STATE_SELECTED_URI = "state_selected_uri";
    private static final String STATE_CAMERA_URI = "state_camera_uri";

    private ImageView imgPillPreview;
    private LinearLayout layoutEmptyPreview;
    private MaterialButton btnStartAnalysis;

    private Uri selectedImageUri;
    private Uri pendingCameraUri;

    /*
     * 카메라 촬영 결과를 받는 코드
     */
    private final ActivityResultLauncher<Uri> cameraLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.TakePicture(),
                    success -> {
                        if (success && pendingCameraUri != null) {
                            showSelectedImage(pendingCameraUri);
                        }
                    }
            );

    /*
     * 갤러리 선택 결과를 받는 코드
     */
    private final ActivityResultLauncher<String> galleryLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri != null) {
                            showSelectedImage(uri);
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pill_capture);

        ImageButton btnBack = findViewById(R.id.btn_back);
        MaterialButton btnCamera = findViewById(R.id.btn_camera);
        MaterialButton btnGallery = findViewById(R.id.btn_gallery);

        imgPillPreview = findViewById(R.id.img_pill_preview);
        layoutEmptyPreview = findViewById(R.id.layout_empty_preview);
        btnStartAnalysis = findViewById(R.id.btn_start_analysis);

        /*
         * 화면 회전 등으로 Activity가 다시 만들어졌을 때
         * 선택했던 이미지 주소 복원
         */
        if (savedInstanceState != null) {
            String selectedUriText =
                    savedInstanceState.getString(STATE_SELECTED_URI);

            String cameraUriText =
                    savedInstanceState.getString(STATE_CAMERA_URI);

            if (cameraUriText != null) {
                pendingCameraUri = Uri.parse(cameraUriText);
            }

            if (selectedUriText != null) {
                showSelectedImage(Uri.parse(selectedUriText));
            }
        }

        // 뒤로가기
        btnBack.setOnClickListener(v -> finish());

        // 카메라 실행
        btnCamera.setOnClickListener(v -> openCamera());

        // 갤러리 실행
        btnGallery.setOnClickListener(v ->
                galleryLauncher.launch("image/*")
        );

        // 분석 화면으로 이동
        btnStartAnalysis.setOnClickListener(v -> {
            if (selectedImageUri == null) {
                Toast.makeText(
                        this,
                        R.string.select_image_first,
                        Toast.LENGTH_SHORT
                ).show();

                return;
            }

            Intent intent = new Intent(
                    PillCaptureActivity.this,
                    PillAnalyzingActivity.class
            );

            intent.putExtra(
                    PillAnalyzingActivity.EXTRA_IMAGE_URI,
                    selectedImageUri.toString()
            );

            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(intent);
        });
    }

    /*
     * 카메라 촬영용 임시 이미지 파일 생성
     */
    private void openCamera() {
        try {
            File imageDirectory = new File(
                    getCacheDir(),
                    "images"
            );

            if (!imageDirectory.exists()
                    && !imageDirectory.mkdirs()) {

                Toast.makeText(
                        this,
                        R.string.camera_file_error,
                        Toast.LENGTH_SHORT
                ).show();

                return;
            }

            File imageFile = File.createTempFile(
                    "pill_",
                    ".jpg",
                    imageDirectory
            );

            pendingCameraUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    imageFile
            );

            cameraLauncher.launch(pendingCameraUri);

        } catch (IOException exception) {
            Toast.makeText(
                    this,
                    R.string.camera_file_error,
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    /*
     * 카메라 또는 갤러리에서 선택한 사진을 미리보기에 표시
     */
    private void showSelectedImage(Uri imageUri) {
        selectedImageUri = imageUri;

        imgPillPreview.setImageDrawable(null);
        imgPillPreview.setImageURI(imageUri);

        imgPillPreview.setVisibility(View.VISIBLE);
        layoutEmptyPreview.setVisibility(View.GONE);

        btnStartAnalysis.setEnabled(true);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (selectedImageUri != null) {
            outState.putString(
                    STATE_SELECTED_URI,
                    selectedImageUri.toString()
            );
        }

        if (pendingCameraUri != null) {
            outState.putString(
                    STATE_CAMERA_URI,
                    pendingCameraUri.toString()
            );
        }
    }
}