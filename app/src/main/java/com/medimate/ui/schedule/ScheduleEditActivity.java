package com.medimate.ui.schedule;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.medimate.R;
import com.medimate.data.medication.MedicationRepository;
import com.medimate.medication.MedicationSchedule;
import com.medimate.medication.ScheduleDraft;
import com.medimate.medication.ScheduleValidator;
import com.medimate.medication.ScheduleVersioning;
import com.medimate.medication.TimeSlot;
import com.medimate.model.Drug;
import com.medimate.tts.TtsManager;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 복약 일정 등록·수정 (README 9-6). 복용량과 시각은 미리 채우지 않고 사용자가 입력한 값만 저장한다.
 * 약봉투에서 읽은 복용법은 참고로만 보여 준다.
 */
public class ScheduleEditActivity extends AppCompatActivity {

    private static final String EXTRA_SCHEDULE_ID = "scheduleId";
    private static final String EXTRA_DATE = "date";
    private static final String EXTRA_DRUG = "drug";

    private static final String MSG_SAVED = "일정을 저장했어요.";
    private static final String MSG_SAVE_FAILED = "일정을 저장하지 못했어요. 다시 로그인해 주세요.";
    private static final String MSG_DUPLICATE_TIME = "이미 등록한 시간이에요.";

    /** 일요일부터 표시 (달력과 같은 순서). 값은 ISO 요일 */
    private static final int[] DAY_ORDER = {7, 1, 2, 3, 4, 5, 6};
    private static final String[] DAY_LABELS = {"일", "월", "화", "수", "목", "금", "토"};

    private MedicationRepository repository;
    private ScheduleEditViewModel viewModel;
    private TtsManager tts;
    private TextInputLayout nameLayout;
    private AutoCompleteTextView nameInput;
    private TextInputLayout dosageLayout;
    private TextInputEditText dosageInput;
    private Button startButton;
    private TextView startNote;
    private MaterialSwitch noEndSwitch;
    private Button endButton;
    private TextView endError;
    private RadioGroup repeatGroup;
    private ChipGroup dayChips;
    private TextView daysError;
    private LinearLayout timesContainer;
    private TextView timesError;
    private MaterialSwitch remindersSwitch;

    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> persist()); // 거부해도 일정은 저장하고, 스케줄 화면에 알림 꺼짐을 표시한다

    public static Intent newIntent(Context context, @Nullable LocalDate date) {
        Intent intent = new Intent(context, ScheduleEditActivity.class);
        return date == null ? intent : intent.putExtra(EXTRA_DATE, date.toString());
    }

    static Intent editIntent(Context context, String scheduleId) {
        return new Intent(context, ScheduleEditActivity.class).putExtra(EXTRA_SCHEDULE_ID, scheduleId);
    }

    /** 약봉투 인식 결과에서 일정 등록으로 넘어온다. 이름만 채우고 나머지는 사용자가 확인·입력한다 */
    public static Intent fromDrug(Context context, Drug drug) {
        return new Intent(context, ScheduleEditActivity.class).putExtra(EXTRA_DRUG, drug);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_schedule_edit);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.schedule_edit_root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        repository = MedicationRepository.get(this);
        if (repository.currentOwner() == null) {
            finish();
            return;
        }
        tts = new TtsManager(this);
        viewModel = new ViewModelProvider(this).get(ScheduleEditViewModel.class);
        bindViews();

        if (viewModel.initialized) {
            renderDraft();
            return;
        }
        String scheduleId = getIntent().getStringExtra(EXTRA_SCHEDULE_ID);
        if (scheduleId != null) {
            repository.loadSchedule(scheduleId, schedule -> {
                if (schedule == null) {
                    finish();
                    return;
                }
                fillFromSchedule(schedule);
                nameInput.setText(schedule.drugName);
                dosageInput.setText(schedule.dosage);
                renderDraft();
            });
        } else {
            fillNew();
            renderDraft();
        }
    }

    private void bindViews() {
        nameLayout = findViewById(R.id.layout_drug_name);
        nameInput = findViewById(R.id.input_drug_name);
        dosageLayout = findViewById(R.id.layout_dosage);
        dosageInput = findViewById(R.id.input_dosage);
        startButton = findViewById(R.id.button_start_date);
        startNote = findViewById(R.id.text_start_note);
        noEndSwitch = findViewById(R.id.switch_no_end);
        endButton = findViewById(R.id.button_end_date);
        endError = findViewById(R.id.text_end_error);
        repeatGroup = findViewById(R.id.radio_repeat);
        dayChips = findViewById(R.id.chip_days);
        daysError = findViewById(R.id.text_days_error);
        timesContainer = findViewById(R.id.container_times);
        timesError = findViewById(R.id.text_times_error);
        remindersSwitch = findViewById(R.id.switch_reminders);

        buildDayChips();
        repository.loadDrugNames(names -> nameInput.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, names)));

        startButton.setOnClickListener(v -> pickDate(viewModel.draft.start, date -> {
            viewModel.draft.start = date;
            renderDates();
        }));
        endButton.setOnClickListener(v -> pickDate(
                viewModel.draft.end != null ? viewModel.draft.end : viewModel.draft.start, date -> {
                    viewModel.draft.end = date;
                    renderDates();
                }));
        noEndSwitch.setOnCheckedChangeListener((b, noEnd) -> {
            if (noEnd) {
                viewModel.draft.end = null;
            } else if (viewModel.draft.end == null) {
                viewModel.draft.end = viewModel.draft.start;
            }
            renderDates();
        });
        repeatGroup.setOnCheckedChangeListener((g, id) -> dayChips.setVisibility(
                id == R.id.radio_selected_days ? View.VISIBLE : View.GONE));
        findViewById(R.id.button_add_time).setOnClickListener(v -> addTime());
        findViewById(R.id.button_save).setOnClickListener(v -> save());
    }

    private void buildDayChips() {
        for (int i = 0; i < DAY_ORDER.length; i++) {
            Chip chip = new Chip(this);
            chip.setId(View.generateViewId());
            chip.setText(DAY_LABELS[i]);
            chip.setTextSize(18);
            chip.setCheckable(true);
            chip.setTag(DAY_ORDER[i]);
            chip.setContentDescription(DAY_LABELS[i] + "요일");
            dayChips.addView(chip);
        }
    }

    private void fillNew() {
        ScheduleDraft draft = viewModel.draft;
        LocalDate today = LocalDate.now();
        String date = getIntent().getStringExtra(EXTRA_DATE);
        LocalDate selected = date == null ? today : LocalDate.parse(date);
        draft.start = selected.isBefore(today) ? today : selected;

        Drug drug = (Drug) getIntent().getSerializableExtra(EXTRA_DRUG);
        if (drug != null) {
            viewModel.sourceName = drug.getName();
            viewModel.sourceMedicationId = drug.getItemSeq();
            nameInput.setText(drug.getName());
            if (!drug.getDosage().isEmpty()) {
                TextView hint = findViewById(R.id.text_dosage_hint);
                hint.setText(getString(R.string.schedule_dosage_ocr_hint, drug.getDosage()));
                hint.setVisibility(View.VISIBLE);
            }
        }
        viewModel.initialized = true;
    }

    private void fillFromSchedule(MedicationSchedule schedule) {
        ScheduleDraft draft = viewModel.draft;
        viewModel.current = schedule;
        viewModel.sourceName = schedule.drugName;
        viewModel.sourceMedicationId = schedule.medicationId;
        draft.start = ScheduleVersioning.hasStarted(schedule, LocalDate.now()) ? LocalDate.now() : schedule.start();
        draft.end = schedule.end();
        draft.days = new ArrayList<>(schedule.daysOfWeek);
        draft.slots = new ArrayList<>(schedule.slots);
        draft.remindersEnabled = schedule.remindersEnabled;
        viewModel.initialized = true;
    }

    private void renderDraft() {
        ScheduleDraft draft = viewModel.draft;
        boolean started = viewModel.current != null
                && ScheduleVersioning.hasStarted(viewModel.current, LocalDate.now());
        TextView title = findViewById(R.id.text_edit_title);
        title.setText(viewModel.current == null ? R.string.schedule_add_title : R.string.schedule_edit_title);
        startButton.setEnabled(!started);
        startNote.setVisibility(started ? View.VISIBLE : View.GONE);

        noEndSwitch.setChecked(draft.end == null);
        renderDates();

        boolean everyDay = draft.days.containsAll(ScheduleDraft.EVERY_DAY);
        repeatGroup.check(everyDay ? R.id.radio_every_day : R.id.radio_selected_days);
        for (int i = 0; i < dayChips.getChildCount(); i++) {
            Chip chip = (Chip) dayChips.getChildAt(i);
            chip.setChecked(!everyDay && draft.days.contains((Integer) chip.getTag()));
        }
        remindersSwitch.setChecked(draft.remindersEnabled);
        renderTimes();
    }

    private void renderDates() {
        ScheduleDraft draft = viewModel.draft;
        startButton.setText(getString(R.string.schedule_start_date, DoseText.fullDate(draft.start)));
        endButton.setVisibility(draft.end == null ? View.GONE : View.VISIBLE);
        if (draft.end != null) {
            endButton.setText(getString(R.string.schedule_end_date, DoseText.fullDate(draft.end)));
        }
        endError.setVisibility(View.GONE);
    }

    private void renderTimes() {
        timesContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        List<TimeSlot> slots = viewModel.draft.slots;
        for (TimeSlot slot : new ArrayList<>(slots)) {
            View row = inflater.inflate(R.layout.item_schedule_time, timesContainer, false);
            String label = DoseText.time(slot.localTime());
            Button time = row.findViewById(R.id.button_time);
            time.setText(label);
            time.setContentDescription(getString(R.string.schedule_time_change_desc, label));
            time.setOnClickListener(v -> pickTime(slot.localTime(), picked -> replaceTime(slot, picked)));
            ImageButton remove = row.findViewById(R.id.button_remove_time);
            remove.setContentDescription(getString(R.string.schedule_time_remove_desc, label));
            remove.setOnClickListener(v -> {
                slots.remove(slot);
                renderTimes();
            });
            timesContainer.addView(row);
        }
        timesError.setVisibility(View.GONE);
    }

    private void addTime() {
        pickTime(LocalTime.of(8, 0), picked -> {
            if (hasTime(picked, null)) {
                say(MSG_DUPLICATE_TIME);
                return;
            }
            viewModel.draft.slots.add(new TimeSlot(UUID.randomUUID().toString(), picked.toString()));
            sortAndRenderTimes();
        });
    }

    /** slotId를 유지해 시간을 고쳐도 같은 회차로 이어지게 한다 */
    private void replaceTime(TimeSlot slot, LocalTime picked) {
        if (hasTime(picked, slot)) {
            say(MSG_DUPLICATE_TIME);
            return;
        }
        List<TimeSlot> slots = viewModel.draft.slots;
        slots.set(slots.indexOf(slot), new TimeSlot(slot.slotId, picked.toString()));
        sortAndRenderTimes();
    }

    private boolean hasTime(LocalTime time, @Nullable TimeSlot except) {
        for (TimeSlot slot : viewModel.draft.slots) {
            if (slot != except && slot.time.equals(time.toString())) {
                return true;
            }
        }
        return false;
    }

    private void sortAndRenderTimes() {
        viewModel.draft.slots.sort(Comparator.comparing(slot -> slot.time));
        renderTimes();
    }

    private interface DatePicked {
        void onPicked(LocalDate date);
    }

    private interface TimePicked {
        void onPicked(LocalTime time);
    }

    private void pickDate(LocalDate initial, DatePicked picked) {
        new DatePickerDialog(this, (view, year, month, day) -> picked.onPicked(LocalDate.of(year, month + 1, day)),
                initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth()).show();
    }

    private void pickTime(LocalTime initial, TimePicked picked) {
        new TimePickerDialog(this, (view, hour, minute) -> picked.onPicked(LocalTime.of(hour, minute)),
                initial.getHour(), initial.getMinute(),
                android.text.format.DateFormat.is24HourFormat(this)).show();
    }

    private void readForm() {
        ScheduleDraft draft = viewModel.draft;
        draft.drugName = nameInput.getText().toString();
        draft.dosage = dosageInput.getText() == null ? "" : dosageInput.getText().toString();
        draft.medicationId = draft.drugName.trim().equals(viewModel.sourceName) ? viewModel.sourceMedicationId : "";
        draft.remindersEnabled = remindersSwitch.isChecked();
        List<Integer> days = new ArrayList<>();
        if (repeatGroup.getCheckedRadioButtonId() == R.id.radio_every_day) {
            days.addAll(ScheduleDraft.EVERY_DAY);
        } else {
            for (int checkedId : dayChips.getCheckedChipIds()) {
                days.add((Integer) dayChips.findViewById(checkedId).getTag());
            }
        }
        draft.days = days;
    }

    private void save() {
        readForm();
        clearErrors();
        ScheduleValidator.Issue issue = ScheduleValidator.validate(viewModel.draft);
        if (issue != null) {
            showIssue(issue);
            return;
        }
        if (viewModel.draft.remindersEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        persist();
    }

    private void persist() {
        MedicationRepository.Result<Boolean> onSaved = saved -> {
            if (!saved) {
                say(MSG_SAVE_FAILED);
                return;
            }
            startActivity(MedicationScheduleActivity.savedIntent(this, viewModel.draft.start, MSG_SAVED));
            finish();
        };
        if (viewModel.current == null) {
            repository.saveNew(viewModel.draft, onSaved);
        } else {
            repository.update(viewModel.current, viewModel.draft, onSaved);
        }
    }

    private void clearErrors() {
        nameLayout.setError(null);
        dosageLayout.setError(null);
        endError.setVisibility(View.GONE);
        daysError.setVisibility(View.GONE);
        timesError.setVisibility(View.GONE);
    }

    private void showIssue(ScheduleValidator.Issue issue) {
        switch (issue.field) {
            case NAME:
                nameLayout.setError(issue.message);
                nameInput.requestFocus();
                break;
            case DOSAGE:
                dosageLayout.setError(issue.message);
                dosageInput.requestFocus();
                break;
            case END:
                showFieldError(endError, issue.message);
                break;
            case DAYS:
                showFieldError(daysError, issue.message);
                break;
            default:
                showFieldError(timesError, issue.message);
                break;
        }
        tts.speak(issue.message);
    }

    private static void showFieldError(TextView view, String message) {
        view.setText(message);
        view.setVisibility(View.VISIBLE);
    }

    private void say(String message) {
        Snackbar.make(findViewById(R.id.schedule_edit_root), message, Snackbar.LENGTH_LONG).show();
        tts.speak(message);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.shutdown();
        }
        super.onDestroy();
    }
}
