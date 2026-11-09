package com.medimate.medication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;

/** 복용 체크 허용 규칙, 입력 검증, 일정 버전 규칙 */
public class ScheduleRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    private static final TimeSlot AM = new TimeSlot("am", "08:00");

    private static ScheduleDraft validDraft() {
        ScheduleDraft draft = new ScheduleDraft();
        draft.drugName = "타이레놀";
        draft.dosage = "1정";
        draft.start = TODAY;
        draft.slots = Collections.singletonList(AM);
        return draft;
    }

    @Test
    public void 미래_날짜는_막고_이른_체크는_확인을_거치고_지난_회차는_허용한다() {
        LocalDateTime now = TODAY.atTime(7, 0);
        LocalTime eight = LocalTime.of(8, 0);
        assertEquals(IntakePolicy.Decision.FUTURE_BLOCKED, IntakePolicy.decide(TODAY.plusDays(1), eight, now));
        assertEquals(IntakePolicy.Decision.NEEDS_EARLY_CONFIRM, IntakePolicy.decide(TODAY, eight, now));
        assertEquals(IntakePolicy.Decision.ALLOWED, IntakePolicy.decide(TODAY, eight, TODAY.atTime(8, 0)));
        assertEquals(IntakePolicy.Decision.ALLOWED, IntakePolicy.decide(TODAY.minusDays(3), eight, now));
    }

    @Test
    public void 올바른_입력은_통과한다() {
        assertNull(ScheduleValidator.validate(validDraft()));
        ScheduleDraft sameDay = validDraft();
        sameDay.end = TODAY;
        assertNull(ScheduleValidator.validate(sameDay));
    }

    @Test
    public void 필수값이_없으면_알려_준다() {
        ScheduleDraft name = validDraft();
        name.drugName = "  ";
        assertEquals(ScheduleValidator.Field.NAME, ScheduleValidator.validate(name).field);

        ScheduleDraft dosage = validDraft();
        dosage.dosage = "";
        assertEquals(ScheduleValidator.Field.DOSAGE, ScheduleValidator.validate(dosage).field);

        ScheduleDraft times = validDraft();
        times.slots = Collections.emptyList();
        assertEquals(ScheduleValidator.Field.TIMES, ScheduleValidator.validate(times).field);

        ScheduleDraft days = validDraft();
        days.days = Collections.emptyList();
        assertEquals(ScheduleValidator.Field.DAYS, ScheduleValidator.validate(days).field);
    }

    @Test
    public void 종료일이_시작일보다_빠르면_거부한다() {
        ScheduleDraft draft = validDraft();
        draft.end = TODAY.minusDays(1);
        assertEquals(ScheduleValidator.Field.END, ScheduleValidator.validate(draft).field);
    }

    @Test
    public void 같은_시간_중복을_거부한다() {
        ScheduleDraft draft = validDraft();
        draft.slots = Arrays.asList(AM, new TimeSlot("other", "08:00"));
        assertEquals(ScheduleValidator.MSG_DUPLICATE_TIME, ScheduleValidator.validate(draft).message);
    }

    @Test
    public void 기간_안에_고른_요일이_없으면_거부한다() {
        ScheduleDraft draft = validDraft();
        draft.end = TODAY; // 월요일 하루
        draft.days = Collections.singletonList(2); // 화요일만
        assertEquals(ScheduleValidator.MSG_NO_DAY_IN_RANGE, ScheduleValidator.validate(draft).message);
    }

    @Test
    public void 입력한_복용량은_앞뒤_공백만_빼고_그대로_저장한다() {
        ScheduleDraft draft = validDraft();
        draft.dosage = " 반 정 ";
        MedicationSchedule s = ScheduleVersioning.fromDraft(draft, "id", "uid", "Asia/Seoul", 1L);
        assertEquals("반 정", s.dosage);
        assertEquals("id", s.seriesId);
        assertEquals(SyncState.PENDING, s.syncState);
    }

    @Test
    public void 이미_시작한_일정을_고치면_어제까지로_닫고_오늘_새_버전을_만든다() {
        MedicationSchedule old = OccurrencesTest.schedule("2026-10-01", null, ScheduleDraft.EVERY_DAY, AM);
        ScheduleVersioning.EditPlan plan =
                ScheduleVersioning.applyEdit(old, validDraft(), TODAY, 9L, "Asia/Seoul", "v2");
        assertEquals("2026-11-01", plan.closed.endDate);
        assertEquals("v1", plan.closed.id);
        assertEquals("v2", plan.active.id);
        assertEquals("series", plan.active.seriesId);
        assertEquals("2026-11-02", plan.active.startDate);
        // 원본 객체는 건드리지 않는다
        assertNull(old.endDate);
    }

    @Test
    public void 아직_시작_전인_일정은_바로_고친다() {
        MedicationSchedule future = OccurrencesTest.schedule("2026-11-10", null, ScheduleDraft.EVERY_DAY, AM);
        ScheduleDraft draft = validDraft();
        draft.start = LocalDate.of(2026, 11, 20);
        ScheduleVersioning.EditPlan plan = ScheduleVersioning.applyEdit(future, draft, TODAY, 9L, "Asia/Seoul", "v2");
        assertNull(plan.closed);
        assertEquals("v1", plan.active.id);
        assertEquals("2026-11-20", plan.active.startDate);
    }

    @Test
    public void 시작_전_일정을_지우면_표시만_하고_시작한_일정은_어제로_닫는다() {
        MedicationSchedule future = OccurrencesTest.schedule("2026-11-10", null, ScheduleDraft.EVERY_DAY, AM);
        assertTrue(ScheduleVersioning.applyDelete(future, TODAY, 9L).deleted);

        MedicationSchedule started = OccurrencesTest.schedule("2026-10-01", null, ScheduleDraft.EVERY_DAY, AM);
        MedicationSchedule closed = ScheduleVersioning.applyDelete(started, TODAY, 9L);
        assertEquals(false, closed.deleted);
        assertEquals("2026-11-01", closed.endDate);
    }

    @Test
    public void 이미_일찍_끝난_일정의_종료일을_늦추지_않는다() {
        MedicationSchedule ended = OccurrencesTest.schedule("2026-10-01", "2026-10-05", ScheduleDraft.EVERY_DAY, AM);
        assertEquals("2026-10-05", ScheduleVersioning.applyDelete(ended, TODAY, 9L).endDate);
    }

    @Test
    public void 수정_대상은_오늘_이후로_이어지는_최신_버전이다() {
        MedicationSchedule closed = OccurrencesTest.schedule("2026-10-01", "2026-11-01", ScheduleDraft.EVERY_DAY, AM);
        MedicationSchedule active = OccurrencesTest.schedule("2026-11-02", null, ScheduleDraft.EVERY_DAY, AM);
        active.id = "v2";
        assertEquals("v2", ScheduleVersioning.editableVersion(Arrays.asList(closed, active), TODAY).id);
        assertNull(ScheduleVersioning.editableVersion(Collections.singletonList(closed), TODAY));
        assertNotNull(ScheduleVersioning.editableVersion(Collections.singletonList(active), TODAY));
    }
}
