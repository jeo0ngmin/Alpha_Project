package com.medimate;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.medimate.data.MyDrugRepository;
import com.medimate.data.firestore.FirestoreMyDrugRepository;
import com.medimate.data.medication.MedicationRepository;
import com.medimate.model.Drug;
import com.medimate.tts.TtsManager;
import com.medimate.ui.EdgeToEdgeHelper;

/**
 * [JE 화면] 약 상세 정보. 카드(약 설명·복용방법·보관방법·주의사항)를 누르면 내용을 보여 주고 읽어 준다.
 * 내용은 서버 결과(봉투 글자·알약 식별 + e약은요)이고, 앱이 만들어 내지 않는다.
 * 아래 버튼으로 내 약에 저장하거나 복용 알림을 만든다.
 */
public class MedicineDetailActivity extends AppCompatActivity {

    private static final String EXTRA_DRUG = "drug";
    private static final String EXTRA_CAN_SAVE = "canSave";
    /** 화면을 열 때 읽어 줄 안내 (알약 식별 결과 등). 없으면 읽지 않는다 */
    public static final String EXTRA_ANNOUNCE = "announce";
    private static final String MSG_EMPTY = "봉투와 공식 자료에서 찾지 못했어요. 약사에게 확인해 주세요.";
    private static final String MSG_NO_INGREDIENT = "성분 정보는 아직 제공하지 않아요. 약사에게 확인해 주세요.";
    private static final String MSG_SAVED = "%s, 내 약에 저장했어요.";
    private static final int SPEAK_MAX_CHARS = 300;
    private static final String MSG_LONG = "내용이 길어서 앞부분만 읽었어요. 나머지는 화면에서 보거나 약사에게 물어봐 주세요.";
    private static final String MSG_LOGIN_REQUIRED = "내 약과 복용 알림은 구글이나 전화번호로 로그인하면 쓸 수 있어요.";

    private TtsManager tts;

    public static Intent newIntent(Context context, Drug drug) {
        return newIntent(context, drug, true);
    }

    /** canSave: 내 약 목록에서 열면 false (이미 저장한 약) */
    public static Intent newIntent(Context context, Drug drug, boolean canSave) {
        return new Intent(context, MedicineDetailActivity.class)
                .putExtra(EXTRA_DRUG, drug)
                .putExtra(EXTRA_CAN_SAVE, canSave);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_medicine_detail);
        EdgeToEdgeHelper.padForSystemBars(findViewById(R.id.medicine_detail_root));

        TextView tvTitle = findViewById(R.id.tv_title);
        TextView tvMedicineName = findViewById(R.id.tv_medicine_name);
        ImageButton btnBack = findViewById(R.id.btn_back);
        tts = new TtsManager(this);

        @SuppressWarnings("deprecation")
        Drug drug = (Drug) getIntent().getSerializableExtra(EXTRA_DRUG);
        if (drug == null) {
            finish();
            return;
        }
        tvTitle.setText(drug.getName());
        tvMedicineName.setText(drug.getName());
        String announce = getIntent().getStringExtra(EXTRA_ANNOUNCE);
        if (announce != null && savedInstanceState == null) {
            tts.speak(announce);
        }

        findViewById(R.id.card_ingredient).setOnClickListener(v -> show("약 성분", MSG_NO_INGREDIENT));
        findViewById(R.id.card_description).setOnClickListener(v -> show("약 설명", drug.getDescription()));
        findViewById(R.id.card_dosage).setOnClickListener(v -> show("복용방법", drug.getDosage()));
        findViewById(R.id.card_storage).setOnClickListener(v -> show("보관방법", drug.getStorage()));
        findViewById(R.id.card_warning).setOnClickListener(v -> showWarning(drug));

        View addMyMedicine = findViewById(R.id.btn_add_my_medicine);
        addMyMedicine.setVisibility(getIntent().getBooleanExtra(EXTRA_CAN_SAVE, true) ? View.VISIBLE : View.GONE);
        addMyMedicine.setOnClickListener(v -> saveToMyMedicine(drug, addMyMedicine));
        findViewById(R.id.btn_add_reminder).setOnClickListener(v -> {
            if (MedicationRepository.get(this).currentOwner() == null) {
                tts.speak(MSG_LOGIN_REQUIRED);
                return;
            }
            startActivity(AddMedicationReminderActivity.fromDrug(this, drug));
        });

        btnBack.setOnClickListener(v -> finish());
    }

    /** 이름·복용법·보관법과 품목기준코드만 저장한다. 효능·주의사항 본문은 저장하지 않는다 (README 9-2) */
    private void saveToMyMedicine(Drug drug, View button) {
        MyDrugRepository repository = new FirestoreMyDrugRepository();
        if (!repository.isAvailable()) {
            tts.speak(MSG_LOGIN_REQUIRED);
            return;
        }
        button.setEnabled(false);
        repository.add(drug.getItemSeq(), drug.getName(), drug.getDosage(), drug.getStorage(),
                MyDrugRepository.SOURCE_OCR, new MyDrugRepository.Callback<Void>() {
                    @Override
                    public void onSuccess(Void value) {
                        tts.speak(String.format(MSG_SAVED, drug.getName()));
                        button.setVisibility(View.GONE);
                    }

                    @Override
                    public void onError(String message) {
                        button.setEnabled(true);
                        tts.speak(message);
                    }
                });
    }

    /** 허가정보처럼 원문이 긴 약은 중요한 항목(경고·금기·고령자)을 먼저 보여 주고, "전체 보기"로 원문을 연다 */
    private void showWarning(Drug drug) {
        if (drug.getWarningFull().isEmpty()) {
            show("주의사항", drug.getWarning());
            return;
        }
        String body = drug.getWarning().trim();
        tts.speak("주의사항 중 꼭 알아야 할 것입니다. " + spokenPart(body));
        new AlertDialog.Builder(this)
                .setTitle("주의사항 (중요한 것만)")
                .setMessage(body)
                .setPositiveButton("닫기", null)
                .setNeutralButton("전체 보기", (d, w) -> show("주의사항 전체", drug.getWarningFull()))
                .show();
    }

    private void show(String title, String text) {
        String body = text == null || text.trim().isEmpty() ? MSG_EMPTY : text.trim();
        tts.speak(title + "입니다. " + spokenPart(body));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("닫기", null)
                .show();
    }

    /**
     * 허가정보 주의사항처럼 아주 긴 글은 앞부분(문장·줄 단위)만 읽고, 나머지는 화면에서 보게 안내한다.
     * 전부 읽으면 몇 분씩 걸려 다른 안내를 들을 수 없다.
     */
    static String spokenPart(String body) {
        if (body.length() <= SPEAK_MAX_CHARS) {
            return body;
        }
        StringBuilder out = new StringBuilder();
        for (String piece : body.split("(?<=[.!?])\\s+|\\n")) {
            if (out.length() > 0 && out.length() + piece.length() > SPEAK_MAX_CHARS) {
                break;
            }
            out.append(out.length() > 0 ? " " : "").append(piece.trim());
        }
        if (out.length() == 0) {
            out.append(body, 0, SPEAK_MAX_CHARS);
        }
        return out + " " + MSG_LONG;
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.shutdown();
        }
        super.onDestroy();
    }
}
