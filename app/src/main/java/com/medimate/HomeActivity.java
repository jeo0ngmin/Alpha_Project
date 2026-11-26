package com.medimate;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.medimate.data.medication.MedicationRepository;
import com.medimate.medication.DoseItem;
import com.medimate.tts.TtsManager;
import com.medimate.ui.EdgeToEdgeHelper;
import com.medimate.ui.ProfileActivity;
import com.medimate.ui.schedule.DoseActions;
import com.medimate.ui.schedule.DoseText;
import com.medimate.ui.schedule.TodayDosesViewModel;

import java.util.List;

/** [JE 화면] 홈. 오늘 복약 진행률·오늘 회차(눌러서 복용 기록), 빠른 기능, 하단 메뉴. */
public class HomeActivity extends AppCompatActivity {

    private static final String MSG_LOGIN_REQUIRED =
            "복약 일정은 구글이나 전화번호로 로그인하면 쓸 수 있어요.";
    private static final String MSG_NO_DOSE = "오늘은 복용할 약이 없어요.";

    private TtsManager tts;
    private TodayDosesViewModel viewModel;
    private DoseActions doseActions;
    private LinearLayout doseList;
    private TextView emptyText;
    private TextView countText;
    private LinearProgressIndicator progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        EdgeToEdgeHelper.padForSystemBars(findViewById(R.id.home_root));

        tts = new TtsManager(this);
        doseList = findViewById(R.id.layout_today_doses);
        emptyText = findViewById(R.id.tv_today_empty);
        countText = findViewById(R.id.tv_medication_count);
        progress = findViewById(R.id.progress_today);

        // 현재 홈 탭 강조
        TextView homeText = findViewById(R.id.tv_nav_home);
        ImageView homeIcon = findViewById(R.id.iv_nav_home);
        homeText.setTextColor(getColor(R.color.medimate_sage_dark));
        homeIcon.setColorFilter(getColor(R.color.medimate_sage_dark));

        // 빠른 기능
        findViewById(R.id.card_bag_analysis).setOnClickListener(v ->
                startActivity(new Intent(this, BagCaptureActivity.class)));
        findViewById(R.id.card_pill_analysis).setOnClickListener(v ->
                startActivity(new Intent(this, PillCaptureActivity.class)));
        findViewById(R.id.card_my_medicine).setOnClickListener(v ->
                startActivity(new Intent(this, MyMedicineActivity.class)));
        findViewById(R.id.btn_profile).setOnClickListener(v ->
                startActivity(new Intent(this, ProfileActivity.class)));
        findViewById(R.id.tv_view_reminder).setOnClickListener(v -> openReminder());

        // 하단 네비게이션 (홈은 현재 화면)
        findViewById(R.id.nav_home).setOnClickListener(v -> { });
        findViewById(R.id.nav_reminder).setOnClickListener(v -> openReminder());
        findViewById(R.id.nav_analysis).setOnClickListener(v ->
                startActivity(new Intent(this, PillCaptureActivity.class)));
        findViewById(R.id.nav_chatbot).setOnClickListener(v ->
                startActivity(new Intent(this, ChatbotActivity.class)));

        MedicationRepository repository = MedicationRepository.get(this);
        if (repository.currentOwner() == null) {
            // 카카오 사용자는 아직 Firebase 사용자가 아니어서 일정을 저장할 수 없다 (README 7-5)
            countText.setText("-");
            emptyText.setText(MSG_LOGIN_REQUIRED);
            emptyText.setVisibility(View.VISIBLE);
            return;
        }
        doseActions = new DoseActions(this, repository, tts, this::say);
        viewModel = new ViewModelProvider(this).get(TodayDosesViewModel.class);
        viewModel.doses().observe(this, this::render);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (viewModel != null) {
            viewModel.refreshNow();
        }
    }

    private void render(List<DoseItem> doses) {
        int taken = TodayDosesViewModel.takenCount(doses);
        countText.setText(getString(R.string.home_progress_count, taken, doses.size()));
        countText.setContentDescription(getString(R.string.home_progress_desc, doses.size(), taken));
        progress.setProgressCompat(doses.isEmpty() ? 0 : taken * 100 / doses.size(), true);

        doseList.removeAllViews();
        emptyText.setText(MSG_NO_DOSE);
        emptyText.setVisibility(doses.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (DoseItem dose : doses) {
            View row = inflater.inflate(R.layout.item_home_dose, doseList, false);
            bindDose(row, dose);
            doseList.addView(row);
        }
    }

    private void bindDose(View row, DoseItem dose) {
        TextView title = row.findViewById(R.id.tv_dose_title);
        TextView detail = row.findViewById(R.id.tv_dose_detail);
        MaterialButton take = row.findViewById(R.id.btn_dose_take);

        String time = DoseText.time(dose.time);
        title.setText(getString(R.string.home_dose_title, periodOf(dose)));
        detail.setText(getString(R.string.home_dose_detail, time, dose.drugName, dose.dosage));

        boolean taken = dose.status == DoseItem.Status.TAKEN;
        take.setText(taken ? R.string.home_dose_taken : R.string.home_dose_take);
        take.setTextColor(getColor(taken ? R.color.white : R.color.medimate_sage_dark));
        take.setBackgroundTintList(ColorStateList.valueOf(
                getColor(taken ? R.color.medimate_sage : R.color.medimate_card)));
        take.setStrokeColor(ColorStateList.valueOf(getColor(R.color.medimate_sage)));
        take.setStrokeWidth(taken ? 0 : Math.round(getResources().getDisplayMetrics().density));
        take.setContentDescription(getString(taken ? R.string.home_dose_taken_desc : R.string.home_dose_take_desc,
                dose.drugName, time, dose.status.label));
        take.setOnClickListener(v -> doseActions.onTap(dose));
    }

    /** "아침 복약", "점심 복약" … 시각으로만 나눈다 */
    private static String periodOf(DoseItem dose) {
        int hour = dose.time.getHour();
        if (hour >= 4 && hour < 11) {
            return "아침";
        } else if (hour >= 11 && hour < 16) {
            return "점심";
        } else if (hour >= 16 && hour < 21) {
            return "저녁";
        }
        return "자기 전";
    }

    private void openReminder() {
        startActivity(MedicationReminderActivity.newIntent(this, null));
    }

    private void say(String message) {
        Snackbar.make(findViewById(R.id.home_root), message, Snackbar.LENGTH_LONG).show();
        tts.speak(message);
    }

    @Override
    protected void onDestroy() {
        tts.shutdown();
        super.onDestroy();
    }
}
