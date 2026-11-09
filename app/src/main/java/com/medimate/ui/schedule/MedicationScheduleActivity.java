package com.medimate.ui.schedule;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.medimate.R;
import com.medimate.data.medication.MedicationRepository;
import com.medimate.medication.DoseItem;
import com.medimate.medication.IntakePolicy;
import com.medimate.medication.MedicationSchedule;
import com.medimate.reminder.ReminderStatus;
import com.medimate.tts.TtsManager;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 복약 스케줄 (README 9-6). 위쪽 달력에서 날짜를 고르면 그날의 복용 회차를 알약 버튼으로 보여 준다.
 * 안내는 화면 메시지와 함께 음성으로도 제공한다.
 */
public class MedicationScheduleActivity extends AppCompatActivity {

    private static final String EXTRA_DATE = "date";
    private static final String EXTRA_ANNOUNCE = "announce";
    private static final long REFRESH_INTERVAL_MS = 30_000;

    private static final String MSG_LOGIN_REQUIRED =
            "복약 스케줄은 구글이나 전화번호로 로그인하면 쓸 수 있어요. 카카오 로그인은 아직 지원하지 않아요.";
    private static final String MSG_FUTURE = "아직 오지 않은 날짜는 복용 완료로 기록할 수 없어요.";
    private static final String MSG_NOT_FOUND = "이 복용 기록을 찾지 못했어요. 화면을 다시 열어 주세요.";
    private static final String MSG_ENDED = "이미 끝났거나 삭제한 일정이라 수정할 수 없어요.";
    private static final String MSG_DELETED = "일정을 삭제했어요. 지난 복용 기록은 그대로 남아 있어요.";
    private static final String MSG_CANCELLED = "복용 완료를 취소했어요.";
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
    private TtsManager tts;
    private CalendarDayAdapter calendarAdapter;
    private DoseAdapter doseAdapter;
    private GridLayoutManager calendarLayout;
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
        Intent intent = new Intent(context, MedicationScheduleActivity.class);
        return date == null ? intent : intent.putExtra(EXTRA_DATE, date.toString());
    }

    /** 일정을 저장한 뒤 그 날짜의 스케줄로 돌아와 결과를 읽어 준다 */
    static Intent savedIntent(Context context, LocalDate date, String message) {
        return newIntent(context, date)
                .putExtra(EXTRA_ANNOUNCE, message)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_medication_schedule);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.schedule_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        tts = new TtsManager(this);
        repository = MedicationRepository.get(this);
        summary = findViewById(R.id.text_summary);
        empty = findViewById(R.id.text_empty);

        // 카카오 사용자는 아직 Firebase 사용자가 아니어서 서버에 저장할 수 없다 (README 7-5)
        if (repository.currentOwner() == null) {
            findViewById(R.id.schedule_content).setVisibility(View.GONE);
            findViewById(R.id.button_add_schedule).setVisibility(View.GONE);
            empty.setText(MSG_LOGIN_REQUIRED);
            empty.setVisibility(View.VISIBLE);
            tts.speak(MSG_LOGIN_REQUIRED);
            return;
        }

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
        findViewById(R.id.button_add_schedule).setOnClickListener(v ->
                startActivity(ScheduleEditActivity.newIntent(this, selectedDate)));
        syncBannerButton.setOnClickListener(v -> {
            repository.retryFailedSync();
            say(MSG_SYNC_RETRY);
        });

        RecyclerView calendar = findViewById(R.id.recycler_calendar);
        calendarLayout = new GridLayoutManager(this, 7);
        calendar.setLayoutManager(calendarLayout);
        calendar.setItemAnimator(null);
        calendarAdapter = new CalendarDayAdapter(date -> viewModel.selectDate(date));
        calendar.setAdapter(calendarAdapter);

        RecyclerView list = findViewById(R.id.recycler_doses);
        list.setLayoutManager(new LinearLayoutManager(this));
        doseAdapter = new DoseAdapter(this::onDoseTapped, this::onDoseMore);
        list.setAdapter(doseAdapter);
    }

    /** 월간 달력 위의 요일 줄 */
    private void buildWeekdayHeader() {
        LocalDate sunday = LocalDate.of(2026, 11, 1);
        for (int i = 0; i < 7; i++) {
            TextView label = new TextView(this);
            label.setText(DoseText.weekday(sunday.plusDays(i)));
            label.setGravity(android.view.Gravity.CENTER);
            label.setTextSize(16);
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

    // 복용 체크

    private void onDoseTapped(DoseItem dose) {
        if (dose.status == DoseItem.Status.TAKEN) {
            confirmCancel(dose);
            return;
        }
        IntakePolicy.Decision decision = IntakePolicy.decide(dose.date, dose.time, LocalDateTime.now());
        if (decision == IntakePolicy.Decision.FUTURE_BLOCKED) {
            say(MSG_FUTURE);
        } else if (decision == IntakePolicy.Decision.NEEDS_EARLY_CONFIRM) {
            confirmEarly(dose);
        } else {
            mark(dose, false);
        }
    }

    private void mark(DoseItem dose, boolean allowEarly) {
        repository.markTaken(dose, allowEarly, result -> {
            switch (result) {
                case RECORDED:
                    say(getString(R.string.schedule_taken_recorded, dose.drugName));
                    break;
                case NEEDS_EARLY_CONFIRM:
                    confirmEarly(dose);
                    break;
                case FUTURE_BLOCKED:
                    say(MSG_FUTURE);
                    break;
                case NOT_FOUND:
                    say(MSG_NOT_FOUND);
                    break;
                default:
                    // 이미 완료된 회차: 기록은 그대로이고 화면이 곧 갱신된다
                    break;
            }
        });
    }

    private void confirmEarly(DoseItem dose) {
        String message = getString(R.string.schedule_early_message, DoseText.time(dose.time), dose.drugName);
        tts.speak(message);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.schedule_early_title)
                .setMessage(message)
                .setPositiveButton(R.string.schedule_early_confirm, (d, w) -> mark(dose, true))
                .setNegativeButton(R.string.schedule_dialog_keep, null)
                .show();
    }

    private void confirmCancel(DoseItem dose) {
        String message = getString(R.string.schedule_cancel_message, dose.drugName, DoseText.time(dose.time));
        tts.speak(message);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.schedule_cancel_title)
                .setMessage(message)
                .setPositiveButton(R.string.schedule_cancel_confirm, (d, w) ->
                        repository.cancelTaken(dose, cancelled -> say(MSG_CANCELLED)))
                .setNegativeButton(R.string.schedule_dialog_keep, null)
                .show();
    }

    // 일정 수정·삭제

    private void onDoseMore(DoseItem dose) {
        repository.findEditable(dose.seriesId, schedule -> {
            if (schedule == null) {
                say(MSG_ENDED);
                return;
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle(schedule.drugName)
                    .setItems(new CharSequence[]{getString(R.string.schedule_menu_edit),
                            getString(R.string.schedule_menu_delete)}, (d, which) -> {
                        if (which == 0) {
                            startActivity(ScheduleEditActivity.editIntent(this, schedule.id));
                        } else {
                            confirmDelete(schedule);
                        }
                    })
                    .show();
        });
    }

    private void confirmDelete(MedicationSchedule schedule) {
        String message = getString(R.string.schedule_delete_message, schedule.drugName);
        tts.speak(message);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.schedule_delete_title)
                .setMessage(message)
                .setPositiveButton(R.string.schedule_menu_delete, (d, w) ->
                        repository.delete(schedule, done -> say(MSG_DELETED)))
                .setNegativeButton(R.string.schedule_dialog_keep, null)
                .show();
    }

    private void say(String message) {
        Snackbar.make(findViewById(R.id.schedule_root), message, Snackbar.LENGTH_LONG).show();
        tts.speak(message);
    }
}
