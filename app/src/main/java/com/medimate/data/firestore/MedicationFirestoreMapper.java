package com.medimate.data.firestore;

import androidx.annotation.Nullable;

import com.google.firebase.Timestamp;
import com.medimate.medication.IntakeRecord;
import com.medimate.medication.MedicationSchedule;
import com.medimate.medication.TimeSlot;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 일정·복용 기록과 Firestore 문서 필드 변환 (README 9-2). */
final class MedicationFirestoreMapper {

    private MedicationFirestoreMapper() {
    }

    static Map<String, Object> toFields(MedicationSchedule s) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("userId", s.ownerUid);
        fields.put("seriesId", s.seriesId);
        fields.put("medicationId", s.medicationId);
        fields.put("drugName", s.drugName);
        fields.put("dosage", s.dosage);
        fields.put("startDate", s.startDate);
        fields.put("endDate", s.endDate);
        fields.put("daysOfWeek", new ArrayList<>(s.daysOfWeek));
        List<Map<String, Object>> slots = new ArrayList<>();
        for (TimeSlot slot : s.slots) {
            Map<String, Object> slotFields = new LinkedHashMap<>();
            slotFields.put("slotId", slot.slotId);
            slotFields.put("time", slot.time);
            slots.add(slotFields);
        }
        fields.put("slots", slots);
        fields.put("timeZone", s.timeZone);
        fields.put("remindersEnabled", s.remindersEnabled);
        fields.put("deleted", s.deleted);
        fields.put("createdAt", new Timestamp(new Date(s.createdAt)));
        fields.put("updatedAt", s.updatedAt);
        return fields;
    }

    /** 필드가 빠졌거나 형식이 다른 문서면 null */
    @Nullable
    static MedicationSchedule scheduleFrom(String id, String uid, @Nullable Map<String, Object> f) {
        try {
            MedicationSchedule s = new MedicationSchedule();
            s.id = id;
            s.ownerUid = uid;
            s.seriesId = (String) require(f, "seriesId");
            s.medicationId = text(f, "medicationId");
            s.drugName = (String) require(f, "drugName");
            s.dosage = (String) require(f, "dosage");
            s.startDate = (String) require(f, "startDate");
            s.endDate = (String) f.get("endDate");
            for (Object day : (List<?>) require(f, "daysOfWeek")) {
                s.daysOfWeek.add(((Number) day).intValue());
            }
            for (Object slot : (List<?>) require(f, "slots")) {
                Map<?, ?> slotFields = (Map<?, ?>) slot;
                s.slots.add(new TimeSlot((String) slotFields.get("slotId"), (String) slotFields.get("time")));
            }
            s.timeZone = text(f, "timeZone");
            s.remindersEnabled = Boolean.TRUE.equals(f.get("remindersEnabled"));
            s.deleted = Boolean.TRUE.equals(f.get("deleted"));
            s.createdAt = millis(f.get("createdAt"));
            s.updatedAt = ((Number) require(f, "updatedAt")).longValue();
            return s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Map<String, Object> toFields(IntakeRecord r) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("userId", r.ownerUid);
        fields.put("scheduleId", r.scheduleId);
        fields.put("seriesId", r.seriesId);
        fields.put("slotId", r.slotId);
        fields.put("scheduledDate", r.scheduledDate);
        fields.put("scheduledTime", r.scheduledTime);
        fields.put("timeZone", r.timeZone);
        fields.put("drugName", r.drugName);
        fields.put("dosage", r.dosage);
        fields.put("status", r.status);
        fields.put("takenAt", r.takenAt == null ? null : new Timestamp(new Date(r.takenAt)));
        fields.put("updatedAt", r.updatedAt);
        return fields;
    }

    /** 필드가 빠졌거나 형식이 다른 문서면 null */
    @Nullable
    static IntakeRecord intakeFrom(String key, String uid, @Nullable Map<String, Object> f) {
        try {
            IntakeRecord r = new IntakeRecord();
            r.occurrenceKey = key;
            r.ownerUid = uid;
            r.scheduleId = (String) require(f, "scheduleId");
            r.seriesId = (String) require(f, "seriesId");
            r.slotId = (String) require(f, "slotId");
            r.scheduledDate = (String) require(f, "scheduledDate");
            r.scheduledTime = (String) require(f, "scheduledTime");
            r.timeZone = text(f, "timeZone");
            r.drugName = (String) require(f, "drugName");
            r.dosage = (String) require(f, "dosage");
            r.status = (String) require(f, "status");
            r.takenAt = f.get("takenAt") == null ? null : millis(f.get("takenAt"));
            r.updatedAt = ((Number) require(f, "updatedAt")).longValue();
            return r;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Object require(@Nullable Map<String, Object> fields, String name) {
        Object value = fields == null ? null : fields.get(name);
        if (value == null) {
            throw new IllegalArgumentException(name);
        }
        return value;
    }

    private static String text(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        return value == null ? "" : (String) value;
    }

    private static long millis(@Nullable Object value) {
        return value instanceof Timestamp ? ((Timestamp) value).toDate().getTime() : 0L;
    }
}
