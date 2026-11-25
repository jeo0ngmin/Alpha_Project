package com.medimate.medication;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 반복되는 복약 일정의 한 버전 (README 9-6). 이미 시작한 일정을 고치면 이 행은 어제까지로 닫고
 * 같은 seriesId로 새 행을 만들어, 지난 날짜의 일정이 바뀌지 않게 한다.
 */
@Entity(tableName = "medication_schedules", indices = {@Index("ownerUid"), @Index("seriesId")})
public class MedicationSchedule {

    @PrimaryKey
    @NonNull
    public String id = "";
    @NonNull
    public String ownerUid = "";
    /** 버전이 달라도 같은 일정이면 같다. 회차 키에 쓴다 */
    @NonNull
    public String seriesId = "";
    /** e약은요 품목기준코드(itemSeq). 직접 입력한 약이면 "" */
    @NonNull
    public String medicationId = "";
    @NonNull
    public String drugName = "";
    /** 1회 복용량. 사용자가 입력한 그대로 저장한다 */
    @NonNull
    public String dosage = "";
    /** yyyy-MM-dd */
    @NonNull
    public String startDate = "";
    /** yyyy-MM-dd. 종료일 없음이면 null */
    @Nullable
    public String endDate;
    /** ISO 요일 (월=1 ... 일=7) */
    @NonNull
    public List<Integer> daysOfWeek = new ArrayList<>();
    @NonNull
    public List<TimeSlot> slots = new ArrayList<>();
    /** 등록할 때의 시간대. 기록용이며 알림은 기기의 현재 시간대로 계산한다 */
    @NonNull
    public String timeZone = "";
    public boolean remindersEnabled;
    /** 시작 전에 지운 일정. 다른 기기에도 삭제를 알리려고 행을 남긴다 */
    public boolean deleted;
    public long createdAt;
    public long updatedAt;
    public int syncState = SyncState.PENDING;

    public LocalDate start() {
        return LocalDate.parse(startDate);
    }

    @Nullable
    public LocalDate end() {
        return endDate == null ? null : LocalDate.parse(endDate);
    }

    /** 이 날짜에 복용 일정이 있는지 */
    public boolean runsOn(LocalDate date) {
        if (deleted || date.isBefore(start())) {
            return false;
        }
        LocalDate end = end();
        if (end != null && date.isAfter(end)) {
            return false;
        }
        return daysOfWeek.contains(date.getDayOfWeek().getValue());
    }

    public MedicationSchedule copy() {
        MedicationSchedule c = new MedicationSchedule();
        c.id = id;
        c.ownerUid = ownerUid;
        c.seriesId = seriesId;
        c.medicationId = medicationId;
        c.drugName = drugName;
        c.dosage = dosage;
        c.startDate = startDate;
        c.endDate = endDate;
        c.daysOfWeek = new ArrayList<>(daysOfWeek);
        c.slots = new ArrayList<>(slots);
        c.timeZone = timeZone;
        c.remindersEnabled = remindersEnabled;
        c.deleted = deleted;
        c.createdAt = createdAt;
        c.updatedAt = updatedAt;
        c.syncState = syncState;
        return c;
    }
}
