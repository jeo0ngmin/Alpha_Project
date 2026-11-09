package com.medimate.pill;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.medimate.R;
import com.medimate.pill.model.PillHints;
import com.medimate.ui.EdgeToEdgeHelper;
import com.medimate.ui.Speaker;

/**
 * 앞면(필수)·뒷면(선택) 사진과 힌트를 받는다.
 * 사진은 고르는 즉시 업로드용 JPEG(회전 보정, 1600px)로 바꿔 캐시에 저장하고, 그 파일을 결과 화면에 넘긴다.
 */
public class PillCaptureActivity extends AppCompatActivity {

    private enum Side { FRONT, BACK }

    private static final String KEY_FRONT = "front";
    private static final String KEY_BACK = "back";
    private static final String KEY_PENDING_FILE = "pendingFile";
    private static final String KEY_PENDING_SIDE = "pendingSide";
    private static final String KEY_GUIDE_SPOKEN = "guideSpoken";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Speaker speaker;
    private TextView guide;
    private ImageView frontPreview;
    private ImageView backPreview;
    private Spinner shape;
    private ChipGroup colors;
    private TextInputEditText print;
    private MaterialButton identify;

    private File frontFile;
    private File backFile;
    /** 카메라 앱이 사진을 쓰는 중인 파일과 그 면. 카메라 앱이 떠 있는 동안 이 화면이 없어질 수 있어 저장해 둔다. */
    private File pendingCameraFile;
    private Side pendingSide;
    private boolean guideSpoken;

    private ActivityResultLauncher<Uri> takePicture;
    private ActivityResultLauncher<PickVisualMediaRequest> pickFront;
    private ActivityResultLauncher<PickVisualMediaRequest> pickBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.debug_activity_pill_capture);
        EdgeToEdgeHelper.padForSystemBars(findViewById(R.id.root));
        speaker = new Speaker(this);

        guide = findViewById(R.id.tvGuide);
        frontPreview = findViewById(R.id.ivFront);
        backPreview = findViewById(R.id.ivBack);
        shape = findViewById(R.id.spShape);
        colors = findViewById(R.id.cgColors);
        print = findViewById(R.id.etPrint);
        identify = findViewById(R.id.btnIdentify);

        HintViews.setupShapes(shape);
        HintViews.setupColors(colors, 2, () -> speaker.say(guide, getString(R.string.capture_color_limit)));

        takePicture = registerForActivityResult(new ActivityResultContracts.TakePicture(), saved -> {
            if (saved && pendingCameraFile != null && pendingSide != null) {
                prepare(pendingSide, Uri.fromFile(pendingCameraFile));
            }
        });
        pickFront = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(),
                uri -> { if (uri != null) prepare(Side.FRONT, uri); });
        pickBack = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(),
                uri -> { if (uri != null) prepare(Side.BACK, uri); });

        findViewById(R.id.btnFrontCamera).setOnClickListener(v -> openCamera(Side.FRONT));
        findViewById(R.id.btnBackCamera).setOnClickListener(v -> openCamera(Side.BACK));
        PickVisualMediaRequest imagesOnly = new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build();
        findViewById(R.id.btnFrontGallery).setOnClickListener(v -> pickFront.launch(imagesOnly));
        findViewById(R.id.btnBackGallery).setOnClickListener(v -> pickBack.launch(imagesOnly));
        identify.setOnClickListener(v -> startIdentification());

        if (savedInstanceState != null) {
            frontFile = fileOrNull(savedInstanceState.getString(KEY_FRONT));
            backFile = fileOrNull(savedInstanceState.getString(KEY_BACK));
            pendingCameraFile = fileOrNull(savedInstanceState.getString(KEY_PENDING_FILE));
            String side = savedInstanceState.getString(KEY_PENDING_SIDE);
            pendingSide = side == null ? null : Side.valueOf(side);
            guideSpoken = savedInstanceState.getBoolean(KEY_GUIDE_SPOKEN);
        }
        showPreview(Side.FRONT);
        showPreview(Side.BACK);
        if (!guideSpoken) {
            speaker.say(guide, getString(R.string.capture_guide));
            guideSpoken = true;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(KEY_FRONT, frontFile == null ? null : frontFile.getPath());
        out.putString(KEY_BACK, backFile == null ? null : backFile.getPath());
        out.putString(KEY_PENDING_FILE, pendingCameraFile == null ? null : pendingCameraFile.getPath());
        out.putString(KEY_PENDING_SIDE, pendingSide == null ? null : pendingSide.name());
        out.putBoolean(KEY_GUIDE_SPOKEN, guideSpoken);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        speaker.shutdown();
        super.onDestroy();
    }

    private void openCamera(Side side) {
        try {
            File dir = photoDir();
            pendingCameraFile = File.createTempFile("camera_", ".jpg", dir);
            pendingSide = side;
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pendingCameraFile);
            takePicture.launch(uri);
        } catch (IOException | ActivityNotFoundException e) {
            speaker.say(guide, getString(R.string.capture_no_camera));
        }
    }

    /** 원본(카메라 파일 또는 앨범 URI)을 업로드용 JPEG로 바꿔 저장한다. */
    private void prepare(Side side, Uri source) {
        speaker.say(guide, getString(R.string.capture_preparing));
        io.execute(() -> {
            try {
                byte[] jpeg = ImageCompressor.toUploadJpeg(getContentResolver(), source);
                String name = side.name().toLowerCase(Locale.ROOT) + "_" + System.currentTimeMillis() + ".jpg";
                File out = new File(photoDir(), name);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(jpeg);
                }
                runOnUiThread(() -> onPrepared(side, out));
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> speaker.say(guide, getString(R.string.capture_photo_failed)));
            }
        });
    }

    private void onPrepared(Side side, File file) {
        if (isDestroyed()) {
            return;
        }
        if (side == Side.FRONT) {
            frontFile = file;
        } else {
            backFile = file;
        }
        if (pendingCameraFile != null && side == pendingSide) {
            //noinspection ResultOfMethodCallIgnored
            pendingCameraFile.delete(); // 원본은 더 필요 없다
            pendingCameraFile = null;
            pendingSide = null;
        }
        showPreview(side);
        speaker.say(guide, getString(side == Side.FRONT ? R.string.capture_front_done : R.string.capture_back_done));
    }

    private void showPreview(Side side) {
        File file = side == Side.FRONT ? frontFile : backFile;
        ImageView view = side == Side.FRONT ? frontPreview : backPreview;
        if (file != null && file.exists()) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 2; // 미리보기는 800px이면 충분
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), opts);
            view.setImageBitmap(bitmap);
            view.setContentDescription(getString(side == Side.FRONT ? R.string.capture_front_ready
                    : R.string.capture_back_ready));
        } else {
            view.setImageResource(R.drawable.ic_pill_placeholder);
            view.setContentDescription(getString(side == Side.FRONT ? R.string.capture_front_empty
                    : R.string.capture_back_empty));
        }
        identify.setEnabled(frontFile != null && frontFile.exists());
    }

    private void startIdentification() {
        PillHints hints = new PillHints(HintViews.selectedShape(shape), HintViews.selectedColors(colors),
                print.getText() == null ? null : print.getText().toString());
        speaker.stop();
        startActivity(PillResultActivity.intent(this, frontFile, backFile, hints));
    }

    private File photoDir() throws IOException {
        File dir = new File(getCacheDir(), "photos");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("사진 폴더를 만들 수 없습니다: " + dir);
        }
        return dir;
    }

    private static File fileOrNull(String path) {
        return path == null ? null : new File(path);
    }
}
