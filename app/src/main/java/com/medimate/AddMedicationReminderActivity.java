package com.medimate;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Calendar;
import java.util.Locale;

public class AddMedicationReminderActivity extends AppCompatActivity {

    private TextInputLayout tilMedicineName;
    private TextInputLayout tilDose;

    private TextInputEditText etMedicineName;
    private TextInputEditText etDose;

    private ChipGroup chipGroupTimes;

    private MaterialButton btnMon;
    private MaterialButton btnTue;
    private MaterialButton btnWed;
    private MaterialButton btnThu;
    private MaterialButton btnFri;
    private MaterialButton btnSat;
    private MaterialButton btnSun;

    private MaterialButton btnStartDate;
    private MaterialButton btnEndDate;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_add_medication_reminder);


        tilMedicineName =
                findViewById(R.id.til_medicine_name);

        tilDose =
                findViewById(R.id.til_dose);

        etMedicineName =
                findViewById(R.id.et_medicine_name);

        etDose =
                findViewById(R.id.et_dose);

        chipGroupTimes =
                findViewById(R.id.chip_group_times);


        btnMon = findViewById(R.id.chip_mon);
        btnTue = findViewById(R.id.chip_tue);
        btnWed = findViewById(R.id.chip_wed);
        btnThu = findViewById(R.id.chip_thu);
        btnFri = findViewById(R.id.chip_fri);
        btnSat = findViewById(R.id.chip_sat);
        btnSun = findViewById(R.id.chip_sun);


        btnStartDate =
                findViewById(R.id.btn_start_date);

        btnEndDate =
                findViewById(R.id.btn_end_date);


        // 뒤로가기
        findViewById(R.id.btn_back)
                .setOnClickListener(v -> finish());


        // 요일 버튼 설정
        setupDayButtons();


        // 기본 복용 시간
        addTimeChip(8, 0);


        // 시간 추가
        findViewById(R.id.btn_add_time)
                .setOnClickListener(v ->
                        showTimePicker()
                );


        // 날짜 선택
        btnStartDate.setOnClickListener(v ->
                showDatePicker(btnStartDate)
        );

        btnEndDate.setOnClickListener(v ->
                showDatePicker(btnEndDate)
        );


        // 저장
        findViewById(R.id.btn_save_reminder)
                .setOnClickListener(v ->
                        validateAndSave()
                );
    }


    // =========================
    // 요일 버튼 설정
    // =========================

    private void setupDayButtons() {

        MaterialButton[] dayButtons = {
                btnMon,
                btnTue,
                btnWed,
                btnThu,
                btnFri,
                btnSat,
                btnSun
        };


        for (MaterialButton button : dayButtons) {

            button.setCheckable(true);

            updateDayButtonStyle(
                    button,
                    false
            );


            button.addOnCheckedChangeListener(
                    (materialButton, isChecked) ->
                            updateDayButtonStyle(
                                    materialButton,
                                    isChecked
                            )
            );
        }
    }


    // =========================
    // 요일 선택 디자인
    // =========================

    private void updateDayButtonStyle(
            MaterialButton button,
            boolean checked
    ) {

        if (checked) {

            button.setBackgroundTintList(
                    ColorStateList.valueOf(
                            getColor(
                                    R.color.medimate_sage_soft
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


    // =========================
    // 선택된 요일 존재 여부
    // =========================

    private boolean hasSelectedDay() {

        return btnMon.isChecked()
                || btnTue.isChecked()
                || btnWed.isChecked()
                || btnThu.isChecked()
                || btnFri.isChecked()
                || btnSat.isChecked()
                || btnSun.isChecked();
    }


    // =========================
    // 시간 선택
    // =========================

    private void showTimePicker() {

        Calendar calendar =
                Calendar.getInstance();


        int currentHour =
                calendar.get(
                        Calendar.HOUR_OF_DAY
                );

        int currentMinute =
                calendar.get(
                        Calendar.MINUTE
                );


        TimePickerDialog dialog =
                new TimePickerDialog(
                        this,

                        (view, hourOfDay, minute) ->
                                addTimeChip(
                                        hourOfDay,
                                        minute
                                ),

                        currentHour,
                        currentMinute,
                        false
                );


        dialog.show();
    }


    // =========================
    // 시간 Chip 추가
    // =========================

    private void addTimeChip(
            int hour,
            int minute
    ) {

        String timeText =
                formatTime(
                        hour,
                        minute
                );


        if (containsTime(timeText)) {

            Toast.makeText(
                    this,
                    "이미 등록한 시간이에요.",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }


        Chip chip =
                new Chip(this);


        chip.setText(
                timeText
        );

        chip.setTextColor(
                getColor(
                        R.color.medimate_sage_dark
                )
        );

        chip.setTextSize(13);

        chip.setCheckable(false);

        chip.setCloseIconVisible(true);


        chip.setCloseIconTint(
                ColorStateList.valueOf(
                        getColor(
                                R.color.medimate_sage_dark
                        )
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


        float density =
                getResources()
                        .getDisplayMetrics()
                        .density;


        chip.setChipStrokeWidth(
                density
        );


        chip.setOnCloseIconClickListener(v ->
                chipGroupTimes.removeView(
                        chip
                )
        );


        chipGroupTimes.addView(
                chip
        );
    }


    // =========================
    // 시간 중복 확인
    // =========================

    private boolean containsTime(
            String time
    ) {

        for (
                int i = 0;
                i < chipGroupTimes.getChildCount();
                i++
        ) {

            Chip chip =
                    (Chip) chipGroupTimes
                            .getChildAt(i);


            if (
                    time.equals(
                            chip.getText()
                                    .toString()
                    )
            ) {

                return true;
            }
        }


        return false;
    }


    // =========================
    // 시간 표시
    // =========================

    private String formatTime(
            int hour,
            int minute
    ) {

        String period =
                hour < 12
                        ? "오전"
                        : "오후";


        int displayHour =
                hour % 12;


        if (displayHour == 0) {

            displayHour = 12;
        }


        return String.format(
                Locale.KOREA,
                "%s %02d:%02d",
                period,
                displayHour,
                minute
        );
    }


    // =========================
    // 날짜 선택
    // =========================

    private void showDatePicker(
            MaterialButton targetButton
    ) {

        Calendar calendar =
                Calendar.getInstance();


        DatePickerDialog dialog =
                new DatePickerDialog(
                        this,

                        (view,
                         selectedYear,
                         selectedMonth,
                         selectedDay) -> {

                            String date =
                                    String.format(
                                            Locale.KOREA,
                                            "%04d.%02d.%02d",
                                            selectedYear,
                                            selectedMonth + 1,
                                            selectedDay
                                    );

                            targetButton.setText(
                                    date
                            );
                        },

                        calendar.get(
                                Calendar.YEAR
                        ),

                        calendar.get(
                                Calendar.MONTH
                        ),

                        calendar.get(
                                Calendar.DAY_OF_MONTH
                        )
                );


        dialog.show();
    }


    // =========================
    // 입력 검증
    // =========================

    private void validateAndSave() {

        String medicineName =
                etMedicineName.getText() == null
                        ? ""
                        : etMedicineName
                        .getText()
                        .toString()
                        .trim();


        String dose =
                etDose.getText() == null
                        ? ""
                        : etDose
                        .getText()
                        .toString()
                        .trim();


        tilMedicineName.setError(null);
        tilDose.setError(null);


        if (medicineName.isEmpty()) {

            tilMedicineName.setError(
                    "약 이름을 입력해 주세요."
            );

            return;
        }


        if (dose.isEmpty()) {

            tilDose.setError(
                    "1회 복용량을 입력해 주세요."
            );

            return;
        }


        if (
                chipGroupTimes
                        .getChildCount() == 0
        ) {

            Toast.makeText(
                    this,
                    "복용 시간을 한 개 이상 등록해 주세요.",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }


        if (!hasSelectedDay()) {

            Toast.makeText(
                    this,
                    "복용 요일을 선택해 주세요.",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }


        Toast.makeText(
                this,
                "입력 내용을 확인했습니다.",
                Toast.LENGTH_SHORT
        ).show();
    }
}