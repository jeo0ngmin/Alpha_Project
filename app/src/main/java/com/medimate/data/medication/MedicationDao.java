package com.medimate.data.medication;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.medimate.medication.IntakeRecord;
import com.medimate.medication.MedicationSchedule;
import com.medimate.medication.ScheduledAlarm;
import com.medimate.medication.SyncState;

import java.util.List;

@Dao
public abstract class MedicationDao {

    // 일정

    @Query("SELECT * FROM medication_schedules WHERE ownerUid = :owner")
    public abstract LiveData<List<MedicationSchedule>> observeSchedules(String owner);

    @Query("SELECT * FROM medication_schedules WHERE ownerUid = :owner")
    public abstract List<MedicationSchedule> schedules(String owner);

    @Query("SELECT * FROM medication_schedules WHERE ownerUid = :owner AND seriesId = :seriesId")
    public abstract List<MedicationSchedule> seriesVersions(String owner, String seriesId);

    @Nullable
    @Query("SELECT * FROM medication_schedules WHERE id = :id")
    public abstract MedicationSchedule schedule(String id);

    @Query("SELECT DISTINCT drugName FROM medication_schedules WHERE ownerUid = :owner ORDER BY drugName")
    public abstract List<String> drugNames(String owner);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void upsertSchedule(MedicationSchedule schedule);

    @Query("SELECT * FROM medication_schedules WHERE ownerUid = :owner AND syncState = " + SyncState.PENDING)
    public abstract List<MedicationSchedule> pendingSchedules(String owner);

    /** 보내는 사이에 다시 고쳐졌으면(updatedAt이 다르면) 그대로 두어 다음 동기화 때 보낸다 */
    @Query("UPDATE medication_schedules SET syncState = :state WHERE id = :id AND updatedAt = :updatedAt")
    public abstract void setScheduleSyncState(String id, long updatedAt, int state);

    // 복용 기록

    @Query("SELECT * FROM medication_intakes WHERE ownerUid = :owner AND scheduledDate BETWEEN :from AND :to")
    public abstract LiveData<List<IntakeRecord>> observeIntakes(String owner, String from, String to);

    @Nullable
    @Query("SELECT * FROM medication_intakes WHERE occurrenceKey = :key")
    public abstract IntakeRecord intake(String key);

    @Query("SELECT occurrenceKey FROM medication_intakes WHERE ownerUid = :owner AND status = '"
            + IntakeRecord.STATUS_TAKEN + "' AND scheduledDate BETWEEN :from AND :to")
    public abstract List<String> takenKeys(String owner, String from, String to);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void upsertIntake(IntakeRecord record);

    @Query("SELECT * FROM medication_intakes WHERE ownerUid = :owner AND syncState = " + SyncState.PENDING)
    public abstract List<IntakeRecord> pendingIntakes(String owner);

    @Query("UPDATE medication_intakes SET syncState = :state WHERE occurrenceKey = :key AND updatedAt = :updatedAt")
    public abstract void setIntakeSyncState(String key, long updatedAt, int state);

    /**
     * 복용 완료를 기록한다. 이미 완료된 회차면 처음 기록을 유지한다.
     * 연속 터치, 화면과 알림의 동시 체크에도 기록은 한 건이고 체크 시각은 처음 값이다.
     *
     * @return 이번에 새로 완료 처리했으면 true
     */
    @Transaction
    public boolean recordTaken(IntakeRecord record) {
        IntakeRecord existing = intake(record.occurrenceKey);
        if (existing != null && existing.isTaken()) {
            return false;
        }
        upsertIntake(record);
        return true;
    }

    /** @return 완료 상태였던 회차를 취소했으면 true */
    @Transaction
    public boolean recordCancelled(String key, long nowMillis) {
        IntakeRecord existing = intake(key);
        if (existing == null || !existing.isTaken()) {
            return false;
        }
        existing.status = IntakeRecord.STATUS_CANCELLED;
        existing.takenAt = null;
        existing.updatedAt = nowMillis;
        existing.syncState = SyncState.PENDING;
        upsertIntake(existing);
        return true;
    }

    // 동기화 상태

    @Query("SELECT (SELECT COUNT(*) FROM medication_schedules WHERE ownerUid = :owner AND syncState = :state)"
            + " + (SELECT COUNT(*) FROM medication_intakes WHERE ownerUid = :owner AND syncState = :state)")
    public abstract LiveData<Integer> observeSyncCount(String owner, int state);

    @Query("UPDATE medication_schedules SET syncState = " + SyncState.PENDING
            + " WHERE ownerUid = :owner AND syncState = " + SyncState.FAILED)
    abstract void requeueFailedSchedules(String owner);

    @Query("UPDATE medication_intakes SET syncState = " + SyncState.PENDING
            + " WHERE ownerUid = :owner AND syncState = " + SyncState.FAILED)
    abstract void requeueFailedIntakes(String owner);

    @Transaction
    public void requeueFailed(String owner) {
        requeueFailedSchedules(owner);
        requeueFailedIntakes(owner);
    }

    /**
     * 서버에서 받은 일정을 합친다. 아직 못 보낸 기기의 변경이 있으면 기기 값을 지킨다.
     *
     * @return 기기 데이터가 바뀌었으면 true
     */
    @Transaction
    public boolean mergeRemoteSchedule(MedicationSchedule remote) {
        MedicationSchedule local = schedule(remote.id);
        if (local != null && (local.syncState != SyncState.SYNCED || local.updatedAt >= remote.updatedAt)) {
            return false;
        }
        remote.syncState = SyncState.SYNCED;
        upsertSchedule(remote);
        return true;
    }

    /** @return 기기 데이터가 바뀌었으면 true */
    @Transaction
    public boolean mergeRemoteIntake(IntakeRecord remote) {
        IntakeRecord local = intake(remote.occurrenceKey);
        if (local != null && (local.syncState != SyncState.SYNCED || local.updatedAt >= remote.updatedAt)) {
            return false;
        }
        remote.syncState = SyncState.SYNCED;
        upsertIntake(remote);
        return true;
    }

    // 알람 예약 기록

    @Query("SELECT * FROM scheduled_alarms")
    public abstract List<ScheduledAlarm> alarms();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void insertAlarm(ScheduledAlarm alarm);

    @Query("DELETE FROM scheduled_alarms")
    public abstract void clearAlarms();

    // 로그아웃·계정 전환

    @Query("DELETE FROM medication_schedules")
    abstract void clearSchedules();

    @Query("DELETE FROM medication_intakes")
    abstract void clearIntakes();

    @Transaction
    public void clearAll() {
        clearSchedules();
        clearIntakes();
        clearAlarms();
    }
}
