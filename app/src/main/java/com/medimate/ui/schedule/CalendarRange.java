package com.medimate.ui.schedule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/** 달력에 보여 줄 날짜 계산. 한 주는 일요일에 시작한다. */
final class CalendarRange {

    private CalendarRange() {
    }

    /** 이 날짜가 들어 있는 일주일 */
    static List<LocalDate> week(LocalDate date) {
        return days(startOfWeek(date), 7);
    }

    /** 이 달 전체를 덮는 칸들 (앞뒤 달 날짜 포함, 7의 배수) */
    static List<LocalDate> monthGrid(YearMonth month) {
        LocalDate first = startOfWeek(month.atDay(1));
        LocalDate last = month.atEndOfMonth().with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
        return days(first, (int) (last.toEpochDay() - first.toEpochDay()) + 1);
    }

    private static LocalDate startOfWeek(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
    }

    private static List<LocalDate> days(LocalDate first, int count) {
        List<LocalDate> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(first.plusDays(i));
        }
        return result;
    }
}
