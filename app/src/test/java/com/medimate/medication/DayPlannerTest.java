package com.medimate.medication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class DayPlannerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    private static final LocalDateTime NOON = DAY.atTime(12, 0);
    private static final TimeSlot AM = new TimeSlot("am", "08:00");
    private static final TimeSlot PM = new TimeSlot("pm", "20:00");

    private static MedicationSchedule threeTimes() {
        return OccurrencesTest.schedule("2026-11-01", null, ScheduleDraft.EVERY_DAY,
                PM, AM, new TimeSlot("noon", "13:00"));
    }

    private static IntakeRecord taken(MedicationSchedule s, TimeSlot slot, LocalDate date) {
        IntakeRecord r = new IntakeRecord();
        r.occurrenceKey = Occurrences.key(s.seriesId, slot.slotId, date);
        r.ownerUid = s.ownerUid;
        r.scheduleId = s.id;
        r.seriesId = s.seriesId;
        r.slotId = slot.slotId;
        r.scheduledDate = date.toString();
        r.scheduledTime = slot.time;
        r.drugName = s.drugName;
        r.dosage = s.dosage;
        r.status = IntakeRecord.STATUS_TAKEN;
        r.takenAt = 1000L;
        r.syncState = SyncState.SYNCED;
        return r;
    }

    @Test
    public void 같은_약을_시간별로_따로_시간순으로_표시한다() {
        List<DoseItem> doses = DayPlanner.plan(DAY, Collections.singletonList(threeTimes()),
                Collections.emptyList(), NOON);
        assertEquals(3, doses.size());
        assertEquals("08:00", doses.get(0).time.toString());
        assertEquals("13:00", doses.get(1).time.toString());
        assertEquals("20:00", doses.get(2).time.toString());
    }

    @Test
    public void 같은_시간의_여러_약이_모두_나온다() {
        MedicationSchedule a = OccurrencesTest.schedule("2026-11-01", null, ScheduleDraft.EVERY_DAY, AM);
        MedicationSchedule b = OccurrencesTest.schedule("2026-11-01", null, ScheduleDraft.EVERY_DAY,
                new TimeSlot("other", "08:00"));
        b.id = "v2";
        b.seriesId = "series2";
        b.drugName = "가나다약";
        List<DoseItem> doses = DayPlanner.plan(DAY, Arrays.asList(a, b), Collections.emptyList(), NOON);
        assertEquals(2, doses.size());
        assertEquals("가나다약", doses.get(0).drugName);
    }

    @Test
    public void 시간이_지나면_미복용이_아니라_확인_필요로_표시한다() {
        List<DoseItem> doses = DayPlanner.plan(DAY, Collections.singletonList(threeTimes()),
                Collections.emptyList(), NOON);
        assertEquals(DoseItem.Status.NEEDS_CHECK, doses.get(0).status);
        assertEquals(DoseItem.Status.SCHEDULED, doses.get(1).status);
        assertEquals("복용 확인 필요", doses.get(0).status.label);
    }

    @Test
    public void 체크는_그_회차에만_적용된다() {
        MedicationSchedule s = threeTimes();
        List<IntakeRecord> records = Collections.singletonList(taken(s, AM, DAY));
        List<DoseItem> today = DayPlanner.plan(DAY, Collections.singletonList(s), records, NOON);
        assertEquals(DoseItem.Status.TAKEN, today.get(0).status);
        assertEquals(DoseItem.Status.SCHEDULED, today.get(2).status);
        assertEquals(1, DayPlanner.takenCount(today));

        List<DoseItem> tomorrow = DayPlanner.plan(DAY.plusDays(1), Collections.singletonList(s), records, NOON);
        assertEquals(0, DayPlanner.takenCount(tomorrow));
    }

    @Test
    public void 취소한_회차는_다시_확인_필요로_돌아간다() {
        MedicationSchedule s = threeTimes();
        IntakeRecord record = taken(s, AM, DAY);
        record.status = IntakeRecord.STATUS_CANCELLED;
        record.takenAt = null;
        List<DoseItem> doses = DayPlanner.plan(DAY, Collections.singletonList(s),
                Collections.singletonList(record), NOON);
        assertEquals(DoseItem.Status.NEEDS_CHECK, doses.get(0).status);
    }

    @Test
    public void 일정을_고쳐도_완료_기록은_당시_이름과_시각으로_남는다() {
        MedicationSchedule original = OccurrencesTest.schedule("2026-11-01", null, ScheduleDraft.EVERY_DAY, AM);
        IntakeRecord record = taken(original, AM, DAY);

        ScheduleDraft draft = new ScheduleDraft();
        draft.drugName = "새이름";
        draft.dosage = "2정";
        draft.start = DAY;
        draft.slots = Collections.singletonList(new TimeSlot("am", "09:00"));
        ScheduleVersioning.EditPlan plan = ScheduleVersioning.applyEdit(original, draft, DAY, 5L, "Asia/Seoul", "v2");

        List<MedicationSchedule> versions = Arrays.asList(plan.closed, plan.active);
        List<DoseItem> doses = DayPlanner.plan(DAY, versions, Collections.singletonList(record), NOON);
        // 같은 슬롯이므로 오늘 회차가 하나 더 생기지 않고, 기록 당시 값을 보여 준다
        assertEquals(1, doses.size());
        assertEquals("타이레놀", doses.get(0).drugName);
        assertEquals("08:00", doses.get(0).time.toString());
        assertEquals("1정", doses.get(0).dosage);

        List<DoseItem> tomorrow = DayPlanner.plan(DAY.plusDays(1), versions, Collections.emptyList(), NOON);
        assertEquals("새이름", tomorrow.get(0).drugName);
        assertEquals("09:00", tomorrow.get(0).time.toString());

        // 지난 날짜는 이전 버전 그대로
        List<DoseItem> yesterday = DayPlanner.plan(DAY.minusDays(1), versions, Collections.emptyList(), NOON);
        assertEquals("타이레놀", yesterday.get(0).drugName);
    }

    @Test
    public void 일정을_지워도_완료_기록은_남고_미래_회차는_사라진다() {
        MedicationSchedule s = OccurrencesTest.schedule("2026-11-01", null, ScheduleDraft.EVERY_DAY, AM);
        IntakeRecord record = taken(s, AM, DAY.minusDays(1));
        MedicationSchedule closed = ScheduleVersioning.applyDelete(s, DAY, 5L);
        List<MedicationSchedule> versions = Collections.singletonList(closed);

        assertEquals(1, DayPlanner.plan(DAY.minusDays(1), versions, Collections.singletonList(record), NOON).size());
        assertEquals(0, DayPlanner.plan(DAY, versions, Collections.singletonList(record), NOON).size());
        assertEquals(0, DayPlanner.plan(DAY.plusDays(1), versions, Collections.emptyList(), NOON).size());
    }

    @Test
    public void 일정이_있는_날과_완료_기록이_있는_날에_점을_찍는다() {
        // 11-03 시작. 11-01 기록은 일정이 지워졌어도 남아 있다
        MedicationSchedule s = OccurrencesTest.schedule("2026-11-03", "2026-11-03", ScheduleDraft.EVERY_DAY, AM);
        IntakeRecord record = taken(s, AM, LocalDate.of(2026, 11, 1));
        Set<LocalDate> dots = DayPlanner.datesWithDoses(
                Arrays.asList(LocalDate.of(2026, 11, 1), DAY, LocalDate.of(2026, 11, 3)),
                Collections.singletonList(s), Collections.singletonList(record));
        assertTrue(dots.contains(LocalDate.of(2026, 11, 1)));
        assertFalse(dots.contains(DAY));
        assertTrue(dots.contains(LocalDate.of(2026, 11, 3)));
    }
}
