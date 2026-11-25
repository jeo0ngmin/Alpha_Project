package com.medimate.medication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class OccurrencesTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    static MedicationSchedule schedule(String start, String end, List<Integer> days, TimeSlot... slots) {
        MedicationSchedule s = new MedicationSchedule();
        s.id = "v1";
        s.seriesId = "series";
        s.ownerUid = "uid";
        s.drugName = "타이레놀";
        s.dosage = "1정";
        s.startDate = start;
        s.endDate = end;
        s.daysOfWeek = days;
        s.slots = Arrays.asList(slots);
        s.remindersEnabled = true;
        return s;
    }

    private static ZonedDateTime at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, SEOUL);
    }

    private static final TimeSlot MORNING = new TimeSlot("am", "08:00");

    @Test
    public void 같은_회차는_항상_같은_키이고_날짜와_슬롯이_다르면_다르다() {
        LocalDate date = LocalDate.of(2026, 11, 2);
        assertEquals("series_am_20261102", Occurrences.key("series", "am", date));
        assertEquals(Occurrences.key("series", "am", date), Occurrences.key("series", "am", date));
        assertEquals(false, Occurrences.key("series", "am", date).equals(Occurrences.key("series", "pm", date)));
        assertEquals(false, Occurrences.key("series", "am", date).equals(Occurrences.key("series", "am", date.plusDays(1))));
    }

    @Test
    public void 오늘_시각이_남았으면_오늘_지났으면_내일() {
        MedicationSchedule s = schedule("2026-11-01", null, ScheduleDraft.EVERY_DAY, MORNING);
        assertEquals(LocalDate.of(2026, 11, 2),
                Occurrences.next(s, MORNING, at(2, 7, 59), Collections.emptySet()).date);
        // 지난 회차는 다시 울리지 않는다
        assertEquals(LocalDate.of(2026, 11, 3),
                Occurrences.next(s, MORNING, at(2, 8, 0), Collections.emptySet()).date);
    }

    @Test
    public void 복용_완료한_회차는_건너뛰고_이후_일정은_유지한다() {
        MedicationSchedule s = schedule("2026-11-01", null, ScheduleDraft.EVERY_DAY, MORNING);
        HashSet<String> taken = new HashSet<>(Collections.singleton("series_am_20261102"));
        assertEquals(LocalDate.of(2026, 11, 3), Occurrences.next(s, MORNING, at(2, 7, 0), taken).date);
    }

    @Test
    public void 선택한_요일만_따른다() {
        // 2026-11-02는 월요일. 수요일(3)만 복용
        MedicationSchedule s = schedule("2026-11-01", null, Collections.singletonList(3), MORNING);
        assertEquals(LocalDate.of(2026, 11, 4),
                Occurrences.next(s, MORNING, at(2, 7, 0), Collections.emptySet()).date);
    }

    @Test
    public void 시작일_전이면_시작일부터_종료일이_지나면_없음() {
        MedicationSchedule s = schedule("2026-11-10", "2026-11-11", ScheduleDraft.EVERY_DAY, MORNING);
        assertEquals(LocalDate.of(2026, 11, 10),
                Occurrences.next(s, MORNING, at(2, 7, 0), Collections.emptySet()).date);
        assertNull(Occurrences.next(s, MORNING, at(11, 9, 0), Collections.emptySet()));
    }

    @Test
    public void 시간대가_바뀌어도_같은_벽시계_시각으로_해석한다() {
        MedicationSchedule s = schedule("2026-11-01", null, ScheduleDraft.EVERY_DAY, MORNING);
        ZonedDateTime newYork = ZonedDateTime.of(2026, 11, 2, 7, 0, 0, 0, ZoneId.of("America/New_York"));
        Occurrences.Next next = Occurrences.next(s, MORNING, newYork, Collections.emptySet());
        assertEquals(8, next.at.getHour());
        assertEquals(ZoneId.of("America/New_York"), next.at.getZone());
    }

    @Test
    public void 지운_일정은_회차가_없다() {
        MedicationSchedule s = schedule("2026-11-01", null, ScheduleDraft.EVERY_DAY, MORNING);
        s.deleted = true;
        assertNull(Occurrences.next(s, MORNING, at(2, 7, 0), Collections.emptySet()));
    }
}
