package com.medimate;

import android.Manifest;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.medimate.data.medication.MedicationRepository;
import com.medimate.medication.MedicationSchedule;
import com.medimate.medication.ScheduleDraft;
import com.medimate.medication.ScheduleValidator;
import com.medimate.medication.ScheduleVersioning;
import com.medimate.medication.TimeSlot;
import com.medimate.model.Drug;
import com.medimate.tts.TtsManager;
import com.medimate.ui.EdgeToEdgeHelper;
import com.medimate.ui.schedule.DoseText;
import com.medimate.ui.schedule.ScheduleEditViewModel;
import com.medimate.ui.schedule.TimeChoiceDialog;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * [JE 화면] 복용 알림 추가·수정 (README 9-6).
 * 복용량과 시각은 미리 채우지 않고 사용자가 입력한 값만 저장한다.
 * 저장은 MedicationRepository(기기 DB + Firestore 동기화, 알림 예약)가 맡는다.
 */
public class AddMedicationReminderActivity extends AppCompatActivity {

    private static final String EXTRA_SCHEDULE_ID = "scheduleId";
    private static final String EXTRA_DATE = "date";
    private static final String EXTRA_DRUG = "drug";

    private static final String MSG_SAVED = "일정을 저장했어요.";
    private static final String MSG_SAVE_FAILED =
            "일정을 저장하지 못했어요. 다시 로그인해 주세요.";
    private static final String MSG_DUPLICATE_TIME =
            "이미 등록한 시간이에요.";

    private MedicationRepository repository;
    private ScheduleEditViewModel viewModel;
    private TtsManager tts;

    private TextInputLayout nameLayout;
    private TextInputEditText nameInput;

    private TextInputLayout dosageLayout;
    private TextInputEditText dosageInput;

    private ChipGroup timeChips;

    /** 월~일 순서. 값은 ISO 요일(월=1 … 일=7)과 같은 순서 */
    private MaterialButton[] dayButtons;

    private MaterialButton startButton;
    private MaterialButton endButton;

    private CompoundButton remindersSwitch;


    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> persist()
            );


    public static Intent newIntent(
            Context context,
            @Nullable LocalDate date
    ) {

        Intent intent =
                new Intent(
                        context,
                        AddMedicationReminderActivity.class
                );

        return date == null
                ? intent
                : intent.putExtra(
                EXTRA_DATE,
                date.toString()
        );
    }


    static Intent editIntent(
            Context context,
            String scheduleId
    ) {

        return new Intent(
                context,
                AddMedicationReminderActivity.class
        ).putExtra(
                EXTRA_SCHEDULE_ID,
                scheduleId
        );
    }


    /** 약 상세에서 넘어온다. 이름만 채우고 나머지는 사용자가 확인·입력한다 */
    static Intent fromDrug(
            Context context,
            Drug drug
    ) {

        return new Intent(
                context,
                AddMedicationReminderActivity.class
        ).putExtra(
                EXTRA_DRUG,
                drug
        );
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(
                R.layout.activity_add_medication_reminder
        );

        EdgeToEdgeHelper.padForSystemBars(
                findViewById(
                        R.id.add_reminder_root
                )
        );


        repository =
                MedicationRepository.get(this);


        if (repository.currentOwner() == null) {

            finish();

            return;
        }


        tts =
                new TtsManager(this);


        viewModel =
                new ViewModelProvider(this)
                        .get(
                                ScheduleEditViewModel.class
                        );


        bindViews();


        if (viewModel.initialized) {

            renderDraft();

            return;
        }


        String scheduleId =
                getIntent()
                        .getStringExtra(
                                EXTRA_SCHEDULE_ID
                        );


        if (scheduleId != null) {

            repository.loadSchedule(
                    scheduleId,
                    schedule -> {

                        if (schedule == null) {

                            finish();

                            return;
                        }


                        fillFromSchedule(
                                schedule
                        );


                        nameInput.setText(
                                schedule.drugName
                        );


                        dosageInput.setText(
                                schedule.dosage
                        );


                        renderDraft();
                    }
            );

        } else {

            fillNew();

            renderDraft();
        }
    }


    private void bindViews() {

        nameLayout =
                findViewById(
                        R.id.til_medicine_name
                );

        nameInput =
                findViewById(
                        R.id.et_medicine_name
                );

        dosageLayout =
                findViewById(
                        R.id.til_dose
                );

        dosageInput =
                findViewById(
                        R.id.et_dose
                );

        timeChips =
                findViewById(
                        R.id.chip_group_times
                );

        startButton =
                findViewById(
                        R.id.btn_start_date
                );

        endButton =
                findViewById(
                        R.id.btn_end_date
                );

        remindersSwitch =
                findViewById(
                        R.id.switch_notification
                );


        dayButtons =
                new MaterialButton[]{

                        findViewById(
                                R.id.chip_mon
                        ),

                        findViewById(
                                R.id.chip_tue
                        ),

                        findViewById(
                                R.id.chip_wed
                        ),

                        findViewById(
                                R.id.chip_thu
                        ),

                        findViewById(
                                R.id.chip_fri
                        ),

                        findViewById(
                                R.id.chip_sat
                        ),

                        findViewById(
                                R.id.chip_sun
                        )
                };


        String[] dayNames =
                {
                        "월",
                        "화",
                        "수",
                        "목",
                        "금",
                        "토",
                        "일"
                };


        for (
                int i = 0;
                i < dayButtons.length;
                i++
        ) {

            MaterialButton button =
                    dayButtons[i];

            button.setCheckable(
                    true
            );

            button.setContentDescription(
                    dayNames[i]
                            + "요일"
            );

            button.addOnCheckedChangeListener(
                    (b, checked) ->
                            styleDay(
                                    b,
                                    checked
                            )
            );
        }


        findViewById(
                R.id.btn_back
        ).setOnClickListener(
                v -> finish()
        );


        startButton.setOnClickListener(
                v -> pickDate(
                        viewModel.draft.start,
                        false
                )
        );


        endButton.setOnClickListener(
                v -> pickDate(
                        viewModel.draft.end != null
                                ? viewModel.draft.end
                                : viewModel.draft.start,
                        true
                )
        );


        findViewById(
                R.id.btn_add_time
        ).setOnClickListener(
                v -> addTime()
        );


        findViewById(
                R.id.btn_save_reminder
        ).setOnClickListener(
                v -> save()
        );
    }


    /**
     * 요일 버튼 스타일.
     *
     * 선택됨:
     * 진한 세이지 배경 + 흰 글씨
     *
     * 선택 안 됨:
     * 흰 카드 + 세이지 글씨 + 연한 테두리
     */
    private void styleDay(
            MaterialButton button,
            boolean checked
    ) {

        if (checked) {

            button.setBackgroundTintList(
                    ColorStateList.valueOf(
                            getColor(
                                    R.color.medimate_sage
                            )
                    )
            );

            button.setTextColor(
                    getColor(
                            R.color.white
                    )
            );

            button.setStrokeColor(
                    ColorStateList.valueOf(
                            getColor(
                                    R.color.medimate_sage
                            )
                    )
            );

        } else {

            button.setBackgroundTintList(
                    ColorStateList.valueOf(
                            getColor(
                                    R.color.medimate_card
                            )
                    )
            );

            button.setTextColor(
                    getColor(
                            R.color.medimate_sage_dark
                    )
            );

            button.setStrokeColor(
                    ColorStateList.valueOf(
                            getColor(
                                    R.color.medimate_border
                            )
                    )
            );
        }
    }


    private void fillNew() {

        ScheduleDraft draft =
                viewModel.draft;


        LocalDate today =
                LocalDate.now();


        String date =
                getIntent()
                        .getStringExtra(
                                EXTRA_DATE
                        );


        LocalDate selected =
                date == null
                        ? today
                        : LocalDate.parse(
                        date
                );


        draft.start =
                selected.isBefore(today)
                        ? today
                        : selected;


        @SuppressWarnings("deprecation")
        Drug drug =
                (Drug) getIntent()
                        .getSerializableExtra(
                                EXTRA_DRUG
                        );


        if (drug != null) {

            viewModel.sourceName =
                    drug.getName();

            viewModel.sourceMedicationId =
                    drug.getItemSeq();


            nameInput.setText(
                    drug.getName()
            );


            if (!drug.getDosage().isEmpty()) {

                dosageLayout.setHelperText(
                        getString(
                                R.string.schedule_dosage_ocr_hint,
                                drug.getDosage()
                        )
                );
            }
        }


        viewModel.initialized =
                true;
    }


    private void fillFromSchedule(
            MedicationSchedule schedule
    ) {

        ScheduleDraft draft =
                viewModel.draft;


        viewModel.current =
                schedule;

        viewModel.sourceName =
                schedule.drugName;

        viewModel.sourceMedicationId =
                schedule.medicationId;


        draft.start =
                ScheduleVersioning.hasStarted(
                        schedule,
                        LocalDate.now()
                )
                        ? LocalDate.now()
                        : schedule.start();


        draft.end =
                schedule.end();


        draft.days =
                new ArrayList<>(
                        schedule.daysOfWeek
                );


        draft.slots =
                new ArrayList<>(
                        schedule.slots
                );


        draft.remindersEnabled =
                schedule.remindersEnabled;


        viewModel.initialized =
                true;
    }


    private void renderDraft() {

        ScheduleDraft draft =
                viewModel.draft;


        boolean started =
                viewModel.current != null
                        && ScheduleVersioning
                        .hasStarted(
                                viewModel.current,
                                LocalDate.now()
                        );


        TextView title =
                findViewById(
                        R.id.tv_title
                );


        title.setText(
                viewModel.current == null
                        ? R.string.add_reminder_title
                        : R.string.schedule_edit_title
        );


        /*
         * 이미 시작한 일정은
         * 시작일을 바꾸지 않는다.
         */
        startButton.setEnabled(
                !started
        );


        renderDates();


        for (
                int i = 0;
                i < dayButtons.length;
                i++
        ) {

            dayButtons[i].setChecked(
                    draft.days.contains(
                            i + 1
                    )
            );

            styleDay(
                    dayButtons[i],
                    dayButtons[i]
                            .isChecked()
            );
        }


        remindersSwitch.setChecked(
                draft.remindersEnabled
        );


        renderTimes();
    }


    private void renderDates() {

        ScheduleDraft draft =
                viewModel.draft;


        startButton.setText(
                DoseText.fullDate(
                        draft.start
                )
        );


        startButton.setContentDescription(
                getString(
                        R.string.schedule_start_date,
                        DoseText.fullDate(
                                draft.start
                        )
                )
        );


        if (draft.end == null) {

            endButton.setText(
                    R.string.add_reminder_no_end_date
            );

            endButton.setContentDescription(
                    getString(
                            R.string.add_reminder_end_none_desc
                    )
            );

        } else {

            endButton.setText(
                    DoseText.fullDate(
                            draft.end
                    )
            );


            endButton.setContentDescription(
                    getString(
                            R.string.schedule_end_date,
                            DoseText.fullDate(
                                    draft.end
                            )
                    )
            );
        }
    }


    private void renderTimes() {

        timeChips.removeAllViews();


        List<TimeSlot> slots =
                viewModel.draft.slots;


        float density =
                getResources()
                        .getDisplayMetrics()
                        .density;


        for (
                TimeSlot slot
                : new ArrayList<>(
                slots
        )
        ) {

            String label =
                    DoseText.time(
                            slot.localTime()
                    );


            Chip chip =
                    new Chip(this);


            chip.setText(
                    label
            );

            chip.setTextSize(
                    16
            );

            chip.setMinHeight(
                    Math.round(
                            48 * density
                    )
            );


            chip.setTextColor(
                    getColor(
                            R.color.medimate_sage_dark
                    )
            );


            chip.setChipBackgroundColor(
                    ColorStateList.valueOf(
                            getColor(
                                    R.color.medimate_sage_soft
                            )
                    )
            );


            chip.setChipStrokeColor(
                    ColorStateList.valueOf(
                            getColor(
                                    R.color.medimate_sage
                            )
                    )
            );


            chip.setChipStrokeWidth(
                    density
            );


            chip.setCloseIconVisible(
                    true
            );


            chip.setCloseIconTint(
                    ColorStateList.valueOf(
                            getColor(
                                    R.color.medimate_sage_dark
                            )
                    )
            );


            chip.setContentDescription(
                    getString(
                            R.string.schedule_time_change_desc,
                            label
                    )
            );


            chip.setCloseIconContentDescription(
                    getString(
                            R.string.schedule_time_remove_desc,
                            label
                    )
            );


            chip.setOnClickListener(
                    v ->
                            pickTime(
                                    slot.localTime(),
                                    picked ->
                                            replaceTime(
                                                    slot,
                                                    picked
                                            )
                            )
            );


            chip.setOnCloseIconClickListener(
                    v -> {

                        slots.remove(
                                slot
                        );

                        renderTimes();
                    }
            );


            timeChips.addView(
                    chip
            );
        }
    }


    private void addTime() {

        pickTime(
                LocalTime.of(
                        8,
                        0
                ),
                picked -> {

                    if (
                            hasTime(
                                    picked,
                                    null
                            )
                    ) {

                        say(
                                MSG_DUPLICATE_TIME
                        );

                        return;
                    }


                    viewModel
                            .draft
                            .slots
                            .add(
                                    new TimeSlot(
                                            UUID
                                                    .randomUUID()
                                                    .toString(),
                                            picked.toString()
                                    )
                            );


                    sortAndRenderTimes();
                }
        );
    }


    /** slotId를 유지해 시간을 고쳐도 같은 회차로 이어지게 한다 */
    private void replaceTime(
            TimeSlot slot,
            LocalTime picked
    ) {

        if (
                hasTime(
                        picked,
                        slot
                )
        ) {

            say(
                    MSG_DUPLICATE_TIME
            );

            return;
        }


        List<TimeSlot> slots =
                viewModel.draft.slots;


        slots.set(
                slots.indexOf(
                        slot
                ),
                new TimeSlot(
                        slot.slotId,
                        picked.toString()
                )
        );


        sortAndRenderTimes();
    }


    private boolean hasTime(
            LocalTime time,
            @Nullable TimeSlot except
    ) {

        for (
                TimeSlot slot
                : viewModel.draft.slots
        ) {

            if (
                    slot != except
                            && slot.time.equals(
                            time.toString()
                    )
            ) {

                return true;
            }
        }


        return false;
    }


    private void sortAndRenderTimes() {

        viewModel.draft.slots.sort(
                Comparator.comparing(
                        slot -> slot.time
                )
        );


        renderTimes();
    }


    /** 종료일 달력에는 "종료일 없음" 버튼을 함께 둔다 */
    private void pickDate(
            LocalDate initial,
            boolean end
    ) {

        DatePickerDialog dialog =
                new DatePickerDialog(
                        this,
                        (
                                view,
                                year,
                                month,
                                day
                        ) -> {

                            LocalDate date =
                                    LocalDate.of(
                                            year,
                                            month + 1,
                                            day
                                    );


                            if (end) {

                                viewModel
                                        .draft
                                        .end =
                                        date;

                            } else {

                                viewModel
                                        .draft
                                        .start =
                                        date;
                            }


                            renderDates();
                        },
                        initial.getYear(),
                        initial.getMonthValue() - 1,
                        initial.getDayOfMonth()
                );


        if (end) {

            dialog.setButton(
                    DialogInterface.BUTTON_NEUTRAL,
                    getString(
                            R.string.add_reminder_no_end_date
                    ),
                    (
                            d,
                            w
                    ) -> {

                        viewModel
                                .draft
                                .end =
                                null;


                        renderDates();
                    }
            );
        }


        dialog.show();
    }


    /** 시계 대신 휠로 고르고, 고른 시간을 읽어 준다 */
    private void pickTime(
            LocalTime initial,
            TimeChoiceDialog.Picked picked
    ) {

        TimeChoiceDialog.show(
                this,
                initial,
                tts::speak,
                picked
        );
    }


    private void readForm() {

        ScheduleDraft draft =
                viewModel.draft;


        draft.drugName =
                nameInput.getText() == null
                        ? ""
                        : nameInput
                        .getText()
                        .toString();


        draft.dosage =
                dosageInput.getText() == null
                        ? ""
                        : dosageInput
                        .getText()
                        .toString();


        draft.medicationId =
                draft.drugName
                        .trim()
                        .equals(
                                viewModel.sourceName
                        )
                        ? viewModel.sourceMedicationId
                        : "";


        draft.remindersEnabled =
                remindersSwitch
                        .isChecked();


        List<Integer> days =
                new ArrayList<>();


        for (
                int i = 0;
                i < dayButtons.length;
                i++
        ) {

            if (
                    dayButtons[i]
                            .isChecked()
            ) {

                days.add(
                        i + 1
                );
            }
        }


        draft.days =
                days;
    }


    private void save() {

        readForm();


        nameLayout.setError(
                null
        );

        dosageLayout.setError(
                null
        );


        ScheduleValidator.Issue issue =
                ScheduleValidator.validate(
                        viewModel.draft
                );


        if (issue != null) {

            showIssue(
                    issue
            );

            return;
        }


        if (
                viewModel
                        .draft
                        .remindersEnabled
                        && Build.VERSION.SDK_INT
                        >= Build.VERSION_CODES.TIRAMISU
                        && ContextCompat
                        .checkSelfPermission(
                                this,
                                Manifest.permission.POST_NOTIFICATIONS
                        )
                        != PackageManager.PERMISSION_GRANTED
        ) {

            notificationPermission.launch(
                    Manifest.permission.POST_NOTIFICATIONS
            );

            return;
        }


        persist();
    }


    private void persist() {

        MedicationRepository.Result<Boolean> onSaved =
                saved -> {

                    if (!saved) {

                        say(
                                MSG_SAVE_FAILED
                        );

                        return;
                    }


                    startActivity(
                            MedicationReminderActivity
                                    .savedIntent(
                                            this,
                                            viewModel
                                                    .draft
                                                    .start,
                                            MSG_SAVED
                                    )
                    );


                    finish();
                };


        if (viewModel.current == null) {

            repository.saveNew(
                    viewModel.draft,
                    onSaved
            );

        } else {

            repository.update(
                    viewModel.current,
                    viewModel.draft,
                    onSaved
            );
        }
    }


    private void showIssue(
            ScheduleValidator.Issue issue
    ) {

        switch (issue.field) {

            case NAME:

                nameLayout.setError(
                        issue.message
                );

                nameInput.requestFocus();

                tts.speak(
                        issue.message
                );

                break;


            case DOSAGE:

                dosageLayout.setError(
                        issue.message
                );

                dosageInput.requestFocus();

                tts.speak(
                        issue.message
                );

                break;


            default:

                say(
                        issue.message
                );

                break;
        }
    }


    private void say(
            String message
    ) {

        Snackbar.make(
                findViewById(
                        R.id.add_reminder_root
                ),
                message,
                Snackbar.LENGTH_LONG
        ).show();


        tts.speak(
                message
        );
    }


    @Override
    protected void onDestroy() {

        if (tts != null) {

            tts.shutdown();
        }


        super.onDestroy();
    }
}