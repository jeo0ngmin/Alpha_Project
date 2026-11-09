package com.medimate.medication;

import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 일정 수정·삭제 규칙 (README 9-6). 변경은 오늘부터 적용하고 어제까지의 일정과 기록은 그대로 둔다.
 * - 오늘 이후에 시작하는 일정: 그 행을 바로 고치거나 지운다 (지난 회차가 없다)
 * - 이미 시작한 일정: 그 행을 어제로 닫고, 수정이면 오늘 시작하는 새 버전을 만든다
 */
public final class ScheduleVersioning {

    private ScheduleVersioning() {
    }

    public static final class EditPlan {
        /** 어제까지로 닫은 이전 버전. 바로 고쳤으면 null */
        @Nullable
        public final MedicationSchedule closed;
        public final MedicationSchedule active;

        EditPlan(@Nullable MedicationSchedule closed, MedicationSchedule active) {
            this.closed = closed;
            this.active = active;
        }
    }

    /** 어제 이전에 시작했는지. 수정 화면에서 시작일을 고정할지 정한다 */
    public static boolean hasStarted(MedicationSchedule schedule, LocalDate today) {
        return schedule.start().isBefore(today);
    }

    /** 이미 시작한 일정이면 draft.start는 쓰지 않고 오늘부터 적용한다 */
    public static EditPlan applyEdit(MedicationSchedule current, ScheduleDraft draft, LocalDate today,
                                     long nowMillis, String zoneId, String newVersionId) {
        MedicationSchedule active = current.copy();
        applyDraft(active, draft, zoneId, nowMillis);
        if (!hasStarted(current, today)) {
            active.startDate = draft.start.toString();
            return new EditPlan(null, active);
        }
        active.id = newVersionId;
        active.startDate = today.toString();
        active.createdAt = nowMillis;
        return new EditPlan(closeBefore(current, today, nowMillis), active);
    }

    public static MedicationSchedule applyDelete(MedicationSchedule current, LocalDate today, long nowMillis) {
        if (hasStarted(current, today)) {
            return closeBefore(current, today, nowMillis);
        }
        MedicationSchedule deleted = current.copy();
        deleted.deleted = true;
        deleted.updatedAt = nowMillis;
        deleted.syncState = SyncState.PENDING;
        return deleted;
    }

    /** 수정·삭제할 수 있는 버전 (오늘 이후에도 이어지는 최신 행). 끝났거나 지운 일정이면 null */
    @Nullable
    public static MedicationSchedule editableVersion(List<MedicationSchedule> versions, LocalDate today) {
        MedicationSchedule latest = null;
        for (MedicationSchedule version : versions) {
            LocalDate end = version.end();
            if (version.deleted || (end != null && end.isBefore(today))) {
                continue;
            }
            if (latest == null || version.start().isAfter(latest.start())) {
                latest = version;
            }
        }
        return latest;
    }

    public static MedicationSchedule fromDraft(ScheduleDraft draft, String id, String ownerUid, String zoneId,
                                               long nowMillis) {
        MedicationSchedule schedule = new MedicationSchedule();
        schedule.id = id;
        schedule.seriesId = id;
        schedule.ownerUid = ownerUid;
        schedule.startDate = draft.start.toString();
        schedule.createdAt = nowMillis;
        applyDraft(schedule, draft, zoneId, nowMillis);
        return schedule;
    }

    private static void applyDraft(MedicationSchedule target, ScheduleDraft draft, String zoneId, long nowMillis) {
        target.drugName = draft.drugName.trim();
        target.medicationId = draft.medicationId;
        target.dosage = draft.dosage.trim();
        target.endDate = draft.end == null ? null : draft.end.toString();
        target.daysOfWeek = new ArrayList<>(draft.days);
        target.slots = new ArrayList<>(draft.slots);
        target.timeZone = zoneId;
        target.remindersEnabled = draft.remindersEnabled;
        target.updatedAt = nowMillis;
        target.syncState = SyncState.PENDING;
    }

    private static MedicationSchedule closeBefore(MedicationSchedule current, LocalDate today, long nowMillis) {
        MedicationSchedule closed = current.copy();
        LocalDate yesterday = today.minusDays(1);
        LocalDate end = current.end();
        closed.endDate = (end != null && end.isBefore(yesterday) ? end : yesterday).toString();
        closed.updatedAt = nowMillis;
        closed.syncState = SyncState.PENDING;
        return closed;
    }
}
