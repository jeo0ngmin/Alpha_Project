package com.medimate.medication;

import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 등록·수정 화면에서 사용자가 입력한 값. 복용량과 시각은 입력한 그대로 저장한다. */
public class ScheduleDraft {

    public static final List<Integer> EVERY_DAY = Arrays.asList(1, 2, 3, 4, 5, 6, 7);

    public String drugName = "";
    public String medicationId = "";
    public String dosage = "";
    public LocalDate start;
    @Nullable
    public LocalDate end;
    public List<Integer> days = new ArrayList<>(EVERY_DAY);
    public List<TimeSlot> slots = new ArrayList<>();
    public boolean remindersEnabled = true;
}
