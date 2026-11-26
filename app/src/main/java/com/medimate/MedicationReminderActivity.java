package com.medimate;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.medimate.data.medication.MedicationRepository;
import com.medimate.reminder.ReminderStatus;
import com.medimate.tts.TtsManager;
import com.medimate.ui.EdgeToEdgeHelper;
import com.medimate.ui.schedule.CalendarDayAdapter;
import com.medimate.ui.schedule.DoseActions;
import com.medimate.ui.schedule.DoseAdapter;
import com.medimate.ui.schedule.DoseText;
import com.medimate.ui.schedule.MedicationScheduleViewModel;

import java.time.LocalDate;

/**
 * [JE 화면] 복약알림 (README 9-6). 달력 카드에서 날짜를 고르면 그날의 복용 회차를 보여 주고,
 * 회차를 누르면 복용 완료로 기록한다. 안내는 화면 메시지와 함께 음성으로도 제공한다.
 */
public class MedicationReminderActivity extends AppCompatActivity {

    private static final String EXTRA_DATE = "date";
    private static final String EXTRA_ANNOUNCE = "announce";
    private static final long REFRESH_INTERVAL_MS = 30_000;

    private static final String MSG_LOGIN_REQUIRED =
            "복약 일정은 구글이나 전화번호로 로그인하면 쓸 수 있어요. 카카오 로그인은 아직 지원하지 않아요.";
    private static final String MSG_SYNC_RETRY = "다시 저장하고 있어요.";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            viewModel.refreshNow();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private MedicationRepository repository;
    private MedicationScheduleViewModel viewModel;
    private DoseActions doseActions;
    private TtsManager tts;
    private CalendarDayAdapter calendarAdapter;
    private DoseAdapter doseAdapter;
    private MaterialButton monthButton;
    private View previousButton;
    private View nextButton;
    private LinearLayout weekdayHeader;
    private TextView summary;
    private TextView empty;
    private View alarmBanner;
    private TextView alarmBannerText;
    private Button alarmBannerButton;
    private View syncBanner;
    private TextView syncBannerText;
    private Button syncBannerButton;
    private LocalDate selectedDate = LocalDate.now();
    private boolean hasReminders;
    private int pendingCount;
    private int failedCount;

    public static Intent newIntent(Context context, @Nullable LocalDate date) {
        Intent intent = new Intent(context, MedicationReminderActivity.class);
        return date == null ? intent : intent.putExtra(EXTRA_DATE, date.toString());
    }

    /** 일정을 저장한 뒤 그 날짜로 돌아와 결과를 읽어 준다 */
    static Intent savedIntent(Context context, LocalDate date, String message) {
        return newIntent(context, date)
                .putExtra(EXTRA_ANNOUNCE, message)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_medication_reminder);
        EdgeToEdgeHelper.padForSystemBars(findViewById(R.id.reminder_root));

        tts = new TtsManager(this);
        repository = MedicationRepository.get(this);
        summary = findViewById(R.id.tv_selected_date);
        empty = findViewById(R.id.text_empty);
        bindBottomNav();

        // 카카오 사용자는 아직 Firebase 사용자가 아니어서 서버에 저장할 수 없다 (README 7-5)
        if (repository.currentOwner() == null) {
            findViewById(R.id.reminder_content).setVisibility(View.GONE);
            findViewById(R.id.btn_add_med_schedule).setVisibility(View.GONE);
            empty.setText(MSG_LOGIN_REQUIRED);
            empty.setVisibility(View.VISIBLE);
            tts.speak(MSG_LOGIN_REQUIRED);
            return;
        }

        doseActions = new DoseActions(this, repository, tts, this::say);
        viewModel = new ViewModelProvider(this).get(MedicationScheduleViewModel.class);
        bindViews();
        viewModel.state().observe(this, this::render);
        viewModel.pendingCount().observe(this, count -> {
            pendingCount = count == null ? 0 : count;
            renderSyncBanner();
        });
        viewModel.failedCount().observe(this, count -> {
            failedCount = count == null ? 0 : count;
            renderSyncBanner();
        });
        if (savedInstanceState == null) {
            handleIntent(getIntent());
        }
    }

    private void bindBottomNav() {
        TextView reminderText = findViewById(R.id.tv_nav_reminder);
        reminderText.setTextColor(getColor(R.color.medimate_sage_dark));
        findViewById(R.id.iv_nav_reminder).setAlpha(1f);

        findViewById(R.id.nav_home).setOnClickListener(v -> {
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });
        findViewById(R.id.nav_reminder).setOnClickListener(v -> { });
        findViewById(R.id.nav_analysis).setOnClickListener(v ->
                startActivity(new Intent(this, PillCaptureActivity.class)));
        findViewById(R.id.nav_chatbot).setOnClickListener(v ->
                startActivity(new Intent(this, ChatbotActivity.class)));
    }

    private void bindViews() {
        monthButton = findViewById(R.id.button_month);
        previousButton = findViewById(R.id.button_previous);
        nextButton = findViewById(R.id.button_next);
        weekdayHeader = findViewById(R.id.weekday_header);
        alarmBanner = findViewById(R.id.alarm_banner);
        alarmBannerText = findViewById(R.id.text_alarm_banner);
        alarmBannerButton = findViewById(R.id.button_alarm_banner);
        syncBanner = findViewById(R.id.sync_banner);
        syncBannerText = findViewById(R.id.text_sync_banner);
        syncBannerButton = findViewById(R.id.button_sync_banner);

        buildWeekdayHeader();
        monthButton.setOnClickListener(v -> viewModel.toggleExpanded());
        previousButton.setOnClickListener(v -> viewModel.goPrevious());
        nextButton.setOnClickListener(v -> viewModel.goNext());
        findViewById(R.id.button_today).setOnClickListener(v -> viewModel.goToToday());
        findViewById(R.id.btn_add_med_schedule).setOnClickListener(v ->
                startActivity(AddMedicationReminderActivity.newIntent(this, selectedDate)));
        syncBannerButton.setOnClickListener(v -> {
            repository.retryFailedSync();
            say(MSG_SYNC_RETRY);
        });

        RecyclerView calendar = findViewById(R.id.recycler_calendar);
        calendar.setLayoutManager(new GridLayoutManager(this, 7));
        calendar.setItemAnimator(null);
        calendarAdapter = new CalendarDayAdapter(date -> viewModel.selectDate(date));
        calendar.setAdapter(calendarAdapter);

        RecyclerView list = findViewById(R.id.recycler_doses);
        list.setLayoutManager(new LinearLayoutManager(this));
        doseAdapter = new DoseAdapter(doseActions::onTap, dose -> doseActions.onMore(dose,
                schedule -> startActivity(AddMedicationReminderActivity.editIntent(this, schedule.id))));
        list.setAdapter(doseAdapter);
    }

    /** 월간 달력 위의 요일 줄 */
    private void buildWeekdayHeader() {
        LocalDate sunday = LocalDate.of(2026, 11, 1);
        for (int i = 0; i < 7; i++) {
            TextView label = new TextView(this);
            label.setText(DoseText.weekday(sunday.plusDays(i)));
            label.setGravity(Gravity.CENTER);
            label.setTextSize(14);
            label.setTextColor(getColor(R.color.medimate_text_sub));
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            weekdayHeader.addView(label, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (viewModel != null) {
            handleIntent(intent);
        }
    }

    private void handleIntent(Intent intent) {
        String date = intent.getStringExtra(EXTRA_DATE);
        if (date != null) {
            viewModel.selectDate(LocalDate.parse(date));
        }
        String message = intent.getStringExtra(EXTRA_ANNOUNCE);
        if (message != null) {
            say(message);
            intent.removeExtra(EXTRA_ANNOUNCE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (viewModel == null) {
            return;
        }
        // 설정 화면에서 돌아왔을 때 권한 상태를 반영하고, 예약을 점검한다
        repository.checkReminders();
        renderAlarmBanner();
        viewModel.refreshNow();
        handler.postDelayed(refreshTick, REFRESH_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        tts.shutdown();
        super.onDestroy();
    }

    private void render(MedicationScheduleViewModel.ScreenState state) {
        selectedDate = state.selected;
        hasReminders = state.hasReminders;

        monthButton.setText(state.title);
        monthButton.setIconResource(state.expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        monthButton.setContentDescription(getString(state.expanded
                ? R.string.schedule_month_collapse_desc : R.string.schedule_month_expand_desc, state.title));
        previousButton.setContentDescription(getString(state.expanded
                ? R.string.schedule_previous_month_desc : R.string.schedule_previous_week_desc));
        nextButton.setContentDescription(getString(state.expanded
                ? R.string.schedule_next_month_desc : R.string.schedule_next_week_desc));
        weekdayHeader.setVisibility(state.expanded ? View.VISIBLE : View.GONE);
        calendarAdapter.submitList(state.days);

        summary.setText(getString(R.string.schedule_summary, DoseText.date(state.selected),
                state.doses.size(), state.takenCount));
        doseAdapter.submitList(state.doses);
        empty.setVisibility(state.doses.isEmpty() ? View.VISIBLE : View.GONE);
        renderAlarmBanner();
    }

    private void renderAlarmBanner() {
        ReminderStatus status = ReminderStatus.current(this);
        doseAdapter.setNotificationsBlocked(status == ReminderStatus.NOTIFICATIONS_OFF);
        if (status == ReminderStatus.OK || !hasReminders) {
            alarmBanner.setVisibility(View.GONE);
            return;
        }
        alarmBanner.setVisibility(View.VISIBLE);
        alarmBannerText.setText(status == ReminderStatus.NOTIFICATIONS_OFF
                ? R.string.schedule_notifications_off : R.string.schedule_exact_alarm_needed);
        alarmBannerButton.setOnClickListener(v -> {
            try {
                startActivity(status.settingsIntent(this));
            } catch (ActivityNotFoundException e) {
                say(getString(R.string.schedule_settings_unavailable));
            }
        });
    }

    private void renderSyncBanner() {
        if (failedCount > 0) {
            syncBanner.setVisibility(View.VISIBLE);
            syncBannerText.setText(getString(R.string.schedule_sync_failed, failedCount));
            syncBannerButton.setVisibility(View.VISIBLE);
        } else if (pendingCount > 0) {
            syncBanner.setVisibility(View.VISIBLE);
            syncBannerText.setText(getString(R.string.schedule_sync_pending, pendingCount));
            syncBannerButton.setVisibility(View.GONE);
        } else {
            syncBanner.setVisibility(View.GONE);
        }
    }

    private void say(String message) {
        Snackbar.make(findViewById(R.id.reminder_root), message, Snackbar.LENGTH_LONG).show();
        tts.speak(message);
    }
}
