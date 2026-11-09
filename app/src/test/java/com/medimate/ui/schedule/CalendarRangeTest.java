package com.medimate.ui.schedule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

public class CalendarRangeTest {

    @Test
    public void 주간은_일요일부터_7일이고_선택한_날짜를_포함한다() {
        LocalDate monday = LocalDate.of(2026, 11, 2);
        List<LocalDate> week = CalendarRange.week(monday);
        assertEquals(7, week.size());
        assertEquals(LocalDate.of(2026, 11, 1), week.get(0));
        assertEquals(DayOfWeek.SUNDAY, week.get(0).getDayOfWeek());
        assertTrue(week.contains(monday));
    }

    @Test
    public void 월간은_한_달_전체를_덮고_7의_배수다() {
        for (YearMonth month : new YearMonth[]{YearMonth.of(2026, 11), YearMonth.of(2026, 2), YearMonth.of(2027, 5)}) {
            List<LocalDate> grid = CalendarRange.monthGrid(month);
            assertEquals(0, grid.size() % 7);
            assertTrue(grid.contains(month.atDay(1)));
            assertTrue(grid.contains(month.atEndOfMonth()));
            assertEquals(DayOfWeek.SUNDAY, grid.get(0).getDayOfWeek());
            assertEquals(DayOfWeek.SATURDAY, grid.get(grid.size() - 1).getDayOfWeek());
        }
    }
}
