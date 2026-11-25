package com.medimate.medication;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 특정 날짜·특정 회차의 복용 기록 (README 9-6). 기본 키가 회차 키라서 같은 회차는 한 건만 생긴다.
 * 약 이름·복용량·시각을 복사해 두어, 일정을 고치거나 지워도 지난 기록은 그대로 보인다.
 */
@Entity(tableName = "medication_intakes", indices = {@Index({"ownerUid", "scheduledDate"})})
public class IntakeRecord {

    public static final String STATUS_TAKEN = "taken";
    /** 사용자가 완료를 취소함. 다른 기기에도 알리려고 행을 지우지 않는다 */
    public static final String STATUS_CANCELLED = "cancelled";

    @PrimaryKey
    @NonNull
    public String occurrenceKey = "";
    @NonNull
    public String ownerUid = "";
    @NonNull
    public String scheduleId = "";
    @NonNull
    public String seriesId = "";
    @NonNull
    public String slotId = "";
    /** yyyy-MM-dd */
    @NonNull
    public String scheduledDate = "";
    /** HH:mm */
    @NonNull
    public String scheduledTime = "";
    @NonNull
    public String timeZone = "";
    @NonNull
    public String drugName = "";
    @NonNull
    public String dosage = "";
    @NonNull
    public String status = STATUS_TAKEN;
    /** 실제로 복용 완료를 누른 시각 (epoch ms). 취소하면 null */
    @Nullable
    public Long takenAt;
    public long updatedAt;
    public int syncState = SyncState.PENDING;

    public boolean isTaken() {
        return STATUS_TAKEN.equals(status);
    }
}
