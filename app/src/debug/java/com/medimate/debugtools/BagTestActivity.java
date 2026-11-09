package com.medimate.debugtools;

import java.io.File;
import java.io.IOException;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.medimate.net.LocalNetworkPermission;

/**
 * [시험용·디버그 전용] 약봉투 홈. 팀 앱의 약봉투 화면(카메라로 촬영하기 / 갤러리에서 가져오기)과 같은 흐름이다.
 * 사진을 고르면 "분석 결과" 화면으로 넘어간다. 화면 담당 팀원의 실제 화면과 합칠 때는 src/debug 폴더를 빼면 된다.
 */
public class BagTestActivity extends AppCompatActivity {

    private File cameraFile;

    private final ActivityResultLauncher<PickVisualMediaRequest> picker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    openResult(uri);
                }
            });

    private final ActivityResultLauncher<Uri> camera =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), saved -> {
                if (Boolean.TRUE.equals(saved) && cameraFile != null) {
                    openResult(Uri.fromFile(cameraFile));
                }
            });

    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LocalNetworkPermission.requestIfNeeded(this);
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
        LinearLayout body = Ui.page(this, "Medimate");
        body.addView(Ui.text(this, "약봉투를 찍으면 어떤 약인지, 언제 어떻게 먹는지 알려 드려요.", 18, Color.DKGRAY, false));

        LinearLayout cam = Ui.card(this, body, v -> showGuide());
        cam.addView(Ui.text(this, "📷  카메라로 촬영하기", 22, Color.BLACK, true));
        cam.addView(Ui.text(this, "약봉투 전체가 나오게 찍어 주세요", 16, Color.DKGRAY, false));
        cam.setContentDescription("카메라로 촬영하기. 약봉투 전체가 나오게 찍어 주세요");

        LinearLayout gal = Ui.card(this, body, v -> picker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build()));
        gal.addView(Ui.text(this, "🖼  갤러리에서 가져오기", 22, Color.BLACK, true));
        gal.addView(Ui.text(this, "이미 저장된 사진을 분석합니다", 16, Color.DKGRAY, false));
        gal.setContentDescription("갤러리에서 가져오기. 이미 저장된 사진을 분석합니다");

        body.addView(Ui.text(this, "", 8, Color.DKGRAY, false));
        body.addView(Ui.outlined(this, "내 복약 일정 보기", v -> startActivity(new Intent(this, PlanTestActivity.class))));
        body.addView(Ui.outlined(this, "1분 뒤 시험 알림", v -> {
            TestAlarms.ensureChannel(this);
            TestAlarms.testInOneMinute(this);
            new AlertDialog.Builder(this).setMessage("1분 뒤에 시험 알림이 울립니다.").setPositiveButton("확인", null).show();
        }));
        body.addView(Ui.text(this, "시험용 화면입니다. 실제 화면은 화면 담당 팀원이 만듭니다.", 13, Color.GRAY, false));
    }

    /** 팀 앱의 "촬영 시 주의 사항" 안내. */
    private void showGuide() {
        new AlertDialog.Builder(this)
                .setTitle("촬영 시 주의 사항")
                .setMessage("1. 약봉투를 평평한 곳에 펴 주세요.\n2. 약 이름과 복용법이 적힌 면이 모두 나오게 찍어 주세요.\n"
                        + "3. 그림자와 빛 반사가 없는 밝은 곳에서 찍어 주세요.\n4. 흔들리지 않게 잠시 멈춘 뒤 찍어 주세요.")
                .setPositiveButton("다음", (d, w) -> takePicture())
                .setNegativeButton("취소", null)
                .show();
    }

    private void takePicture() {
        try {
            File dir = new File(getCacheDir(), "photos");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("사진 폴더를 만들 수 없습니다.");
            }
            cameraFile = File.createTempFile("bag_", ".jpg", dir);
            camera.launch(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", cameraFile));
        } catch (Exception e) {
            new AlertDialog.Builder(this).setMessage("카메라를 열 수 없습니다: " + e.getMessage()).setPositiveButton("확인", null).show();
        }
    }

    private void openResult(Uri photo) {
        startActivity(new Intent(this, BagResultActivity.class).setData(photo)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }
}
