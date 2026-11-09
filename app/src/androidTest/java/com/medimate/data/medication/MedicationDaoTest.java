package com.medimate.data.medication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.medimate.medication.IntakeRecord;
import com.medimate.medication.MedicationSchedule;
import com.medimate.medication.ScheduleDraft;
import com.medimate.medication.SyncState;
import com.medimate.medication.TimeSlot;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 실제 SQLite에서 회차 중복 방지, 동기화 상태, 서버 병합, 계정 정리를 확인한다 */
@RunWith(AndroidJUnit4.class)
public class MedicationDaoTest {

    private static final String KEY = "series_am_20261102";

    private MedicationDatabase database;
    private MedicationDao dao;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, MedicationDatabase.class).build();
        dao = database.dao();
    }

    @After
    public void tearDown() {
        database.close();
    }

    private static IntakeRecord taken(long takenAt) {
        IntakeRecord r = new IntakeRecord();
        r.occurrenceKey = KEY;
        r.ownerUid = "uid";
        r.scheduleId = "v1";
        r.seriesId = "series";
        r.slotId = "am";
        r.scheduledDate = "2026-11-02";
        r.scheduledTime = "08:00";
        r.drugName = "타이레놀";
        r.dosage = "1정";
        r.status = IntakeRecord.STATUS_TAKEN;
        r.takenAt = takenAt;
        r.updatedAt = takenAt;
        r.syncState = SyncState.PENDING;
        return r;
    }

    private static MedicationSchedule schedule(long updatedAt, int syncState) {
        MedicationSchedule s = new MedicationSchedule();
        s.id = "v1";
        s.ownerUid = "uid";
        s.seriesId = "series";
        s.drugName = "타이레놀";
        s.dosage = "1정";
        s.startDate = "2026-11-01";
        s.daysOfWeek = new ArrayList<>(ScheduleDraft.EVERY_DAY);
        s.slots = Collections.singletonList(new TimeSlot("am", "08:00"));
        s.updatedAt = updatedAt;
        s.syncState = syncState;
        return s;
    }

    @Test
    public void 같은_회차를_여러_번_체크해도_한_건이고_처음_시각을_유지한다() {
        assertTrue(dao.recordTaken(taken(100)));
        assertFalse(dao.recordTaken(taken(200)));
        assertEquals(1, dao.pendingIntakes("uid").size());
        assertEquals(Long.valueOf(100), dao.intake(KEY).takenAt);
    }

    @Test
    public void 취소는_기록을_지우지_않고_상태를_바꾸며_다시_체크할_수_있다() {
        dao.recordTaken(taken(100));
        assertTrue(dao.recordCancelled(KEY, 150));
        IntakeRecord cancelled = dao.intake(KEY);
        assertEquals(IntakeRecord.STATUS_CANCELLED, cancelled.status);
        assertNull(cancelled.takenAt);
        assertFalse(dao.recordCancelled(KEY, 160));

        assertTrue(dao.recordTaken(taken(200)));
        assertEquals(Long.valueOf(200), dao.intake(KEY).takenAt);
    }

    @Test
    public void 보내는_사이에_다시_바뀐_기록은_동기화_완료로_덮지_않는다() {
        dao.recordTaken(taken(100));
        dao.recordCancelled(KEY, 150);
        // 100 버전을 보낸 결과가 뒤늦게 도착
        dao.setIntakeSyncState(KEY, 100, SyncState.SYNCED);
        assertEquals(SyncState.PENDING, dao.intake(KEY).syncState);
        dao.setIntakeSyncState(KEY, 150, SyncState.SYNCED);
        assertEquals(SyncState.SYNCED, dao.intake(KEY).syncState);
    }

    @Test
    public void 못_보낸_기기_변경은_서버_값으로_덮지_않는다() {
        dao.recordTaken(taken(100));
        IntakeRecord remote = taken(50);
        remote.status = IntakeRecord.STATUS_CANCELLED;
        remote.updatedAt = 999;
        assertFalse(dao.mergeRemoteIntake(remote));
        assertTrue(dao.intake(KEY).isTaken());
    }

    @Test
    public void 동기화된_기록은_더_새로운_서버_값으로_바뀐다() {
        dao.recordTaken(taken(100));
        dao.setIntakeSyncState(KEY, 100, SyncState.SYNCED);
        IntakeRecord remote = taken(100);
        remote.status = IntakeRecord.STATUS_CANCELLED;
        remote.takenAt = null;
        remote.updatedAt = 200;
        assertTrue(dao.mergeRemoteIntake(remote));
        assertFalse(dao.intake(KEY).isTaken());
        assertEquals(SyncState.SYNCED, dao.intake(KEY).syncState);
    }

    @Test
    public void 일정_병합도_같은_규칙을_따른다() {
        dao.upsertSchedule(schedule(100, SyncState.PENDING));
        assertFalse(dao.mergeRemoteSchedule(schedule(999, SyncState.SYNCED)));
        dao.setScheduleSyncState("v1", 100, SyncState.SYNCED);
        assertTrue(dao.mergeRemoteSchedule(schedule(200, SyncState.SYNCED)));
        assertEquals(200, dao.schedule("v1").updatedAt);
    }

    @Test
    public void 실패한_항목만_다시_대기로_돌린다() {
        dao.recordTaken(taken(100));
        dao.setIntakeSyncState(KEY, 100, SyncState.FAILED);
        assertEquals(0, dao.pendingIntakes("uid").size());
        dao.requeueFailed("uid");
        assertEquals(1, dao.pendingIntakes("uid").size());
    }

    @Test
    public void 다른_사용자의_데이터는_조회되지_않고_계정_정리로_모두_지워진다() {
        dao.upsertSchedule(schedule(100, SyncState.SYNCED));
        dao.recordTaken(taken(100));
        List<MedicationSchedule> other = dao.schedules("someone-else");
        assertEquals(0, other.size());
        assertEquals(0, dao.pendingIntakes("someone-else").size());

        dao.clearAll();
        assertEquals(0, dao.schedules("uid").size());
        assertNull(dao.intake(KEY));
        assertEquals(0, dao.alarms().size());
    }
}
