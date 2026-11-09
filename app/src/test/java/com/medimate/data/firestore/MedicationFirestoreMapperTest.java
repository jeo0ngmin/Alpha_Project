package com.medimate.data.firestore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.medimate.medication.IntakeRecord;
import com.medimate.medication.MedicationSchedule;
import com.medimate.medication.TimeSlot;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class MedicationFirestoreMapperTest {

    @Test
    public void 일정을_저장했다가_읽으면_같은_값이다() {
        MedicationSchedule s = new MedicationSchedule();
        s.id = "v1";
        s.ownerUid = "uid";
        s.seriesId = "series";
        s.medicationId = "200";
        s.drugName = "타이레놀";
        s.dosage = "1정";
        s.startDate = "2026-11-02";
        s.endDate = null;
        s.daysOfWeek = Arrays.asList(1, 3);
        s.slots = Collections.singletonList(new TimeSlot("am", "08:00"));
        s.timeZone = "Asia/Seoul";
        s.remindersEnabled = true;
        s.createdAt = 1_000L;
        s.updatedAt = 2_000L;

        Map<String, Object> fields = MedicationFirestoreMapper.toFields(s);
        // Firestore는 정수를 Long으로 돌려준다
        fields.put("daysOfWeek", Arrays.asList(1L, 3L));
        MedicationSchedule read = MedicationFirestoreMapper.scheduleFrom("v1", "uid", fields);

        assertEquals("series", read.seriesId);
        assertEquals("200", read.medicationId);
        assertEquals(Arrays.asList(1, 3), read.daysOfWeek);
        assertEquals(s.slots, read.slots);
        assertNull(read.endDate);
        assertTrue(read.remindersEnabled);
        assertEquals(1_000L, read.createdAt);
        assertEquals(2_000L, read.updatedAt);
    }

    @Test
    public void 복용_기록을_저장했다가_읽으면_체크_시각이_유지된다() {
        IntakeRecord r = new IntakeRecord();
        r.occurrenceKey = "series_am_20261102";
        r.ownerUid = "uid";
        r.scheduleId = "v1";
        r.seriesId = "series";
        r.slotId = "am";
        r.scheduledDate = "2026-11-02";
        r.scheduledTime = "08:00";
        r.drugName = "타이레놀";
        r.dosage = "1정";
        r.status = IntakeRecord.STATUS_TAKEN;
        r.takenAt = 5_000L;
        r.updatedAt = 5_000L;

        IntakeRecord read = MedicationFirestoreMapper.intakeFrom(r.occurrenceKey, "uid",
                MedicationFirestoreMapper.toFields(r));
        assertEquals("series_am_20261102", read.occurrenceKey);
        assertEquals(Long.valueOf(5_000L), read.takenAt);
        assertTrue(read.isTaken());
    }

    @Test
    public void 형식이_다른_문서는_무시한다() {
        assertNull(MedicationFirestoreMapper.scheduleFrom("x", "uid", new HashMap<>()));
        assertNull(MedicationFirestoreMapper.intakeFrom("x", "uid", null));
    }
}
