package com.medimate.ui.schedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** 복약 화면·알림에 쓰는 날짜·시각 표기. */
public final class DoseText {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN);
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("yyyy년 M월 d일", Locale.KOREAN);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy년 M월", Locale.KOREAN);
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("E", Locale.KOREAN);

    private DoseText() {
    }

    public static String time(LocalTime time) {
        return time.format(TIME);
    }

    static String date(LocalDate date) {
        return date.format(DATE);
    }

    static String fullDate(LocalDate date) {
        return date.format(FULL_DATE);
    }

    static String month(YearMonth month) {
        return month.format(MONTH);
    }

    static String weekday(LocalDate date) {
        return date.format(WEEKDAY);
    }
}
