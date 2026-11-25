package com.medimate.medication;

import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/** 등록·수정 입력 검증. 문제가 있으면 사용자에게 읽어 줄 문장을 돌려준다. */
public final class ScheduleValidator {

    public enum Field { NAME, DOSAGE, END, DAYS, TIMES }

    public static final class Issue {
        public final Field field;
        public final String message;

        Issue(Field field, String message) {
            this.field = field;
            this.message = message;
        }
    }

    static final String MSG_NAME = "약 이름을 입력해 주세요.";
    static final String MSG_DOSAGE = "1회 복용량을 입력해 주세요.";
    static final String MSG_END = "종료일은 시작일보다 빠를 수 없어요.";
    static final String MSG_DAYS = "반복할 요일을 하나 이상 골라 주세요.";
    static final String MSG_NO_DAY_IN_RANGE = "시작일과 종료일 사이에 고른 요일이 없어요.";
    static final String MSG_TIMES = "알림 시간을 하나 이상 추가해 주세요.";
    static final String MSG_DUPLICATE_TIME = "같은 시간이 두 번 들어 있어요.";

    private ScheduleValidator() {
    }

    @Nullable
    public static Issue validate(ScheduleDraft draft) {
        if (draft.drugName.trim().isEmpty()) {
            return new Issue(Field.NAME, MSG_NAME);
        }
        if (draft.dosage.trim().isEmpty()) {
            return new Issue(Field.DOSAGE, MSG_DOSAGE);
        }
        if (draft.end != null && draft.end.isBefore(draft.start)) {
            return new Issue(Field.END, MSG_END);
        }
        if (draft.days.isEmpty()) {
            return new Issue(Field.DAYS, MSG_DAYS);
        }
        if (draft.end != null && !hasDayInRange(draft)) {
            return new Issue(Field.DAYS, MSG_NO_DAY_IN_RANGE);
        }
        if (draft.slots.isEmpty()) {
            return new Issue(Field.TIMES, MSG_TIMES);
        }
        Set<String> times = new HashSet<>();
        for (TimeSlot slot : draft.slots) {
            if (!times.add(slot.time)) {
                return new Issue(Field.TIMES, MSG_DUPLICATE_TIME);
            }
        }
        return null;
    }

    private static boolean hasDayInRange(ScheduleDraft draft) {
        LocalDate date = draft.start;
        for (int i = 0; i < 7 && !date.isAfter(draft.end); i++, date = date.plusDays(1)) {
            if (draft.days.contains(date.getDayOfWeek().getValue())) {
                return true;
            }
        }
        return false;
    }
}
