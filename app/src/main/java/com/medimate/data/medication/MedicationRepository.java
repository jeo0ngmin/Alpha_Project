package com.medimate.data.medication;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.medimate.data.firestore.FirestoreMedicationStore;
import com.medimate.medication.DayPlanner;
import com.medimate.medication.DoseItem;
import com.medimate.medication.IntakePolicy;
import com.medimate.medication.IntakeRecord;
import com.medimate.medication.MedicationSchedule;
import com.medimate.medication.Occurrences;
import com.medimate.medication.ScheduleDraft;
import com.medimate.medication.ScheduleVersioning;
import com.medimate.medication.SyncState;
import com.medimate.medication.TimeSlot;
import com.medimate.reminder.ReminderNotifier;
import com.medimate.reminder.ReminderScheduler;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 복약 일정·복용 기록의 단일 창구 (README 9-6). 화면·알림은 이 클래스만 참조한다.
 * 항상 기기(Room)에 먼저 저장하고 서버 동기화는 MedicationSyncWorker가 맡는다.
 * 저장 뒤에는 알람 예약을 다시 맞춘다. 기기 쓰기는 한 스레드에서 차례로 처리한다.
 */
public final class MedicationRepository {

    public enum MarkResult { RECORDED, ALREADY_TAKEN, NEEDS_EARLY_CONFIRM, FUTURE_BLOCKED, NOT_FOUND }

    public interface Result<T> {
        /** 메인 스레드에서 불린다 */
        void onResult(T value);
    }

    private static final String TAG = "MedicationRepo";
    private static final String PREFS = "medication_session";
    private static final String PREF_OWNER = "owner_uid";
    /** 알람이 이보다 늦게 도착하면(절전 등) 때 지난 알림을 띄우지 않는다. 화면에는 복용 확인 필요로 남는다 */
    private static final Duration LATE_ALARM_LIMIT = Duration.ofHours(1);
    /** 서버에서 받아 둘 기록 범위. 그 밖의 날짜는 화면이 그 달을 열 때 받는다 */
    private static final int PULL_PAST_DAYS = 40;
    private static final int TAKEN_LOOKAHEAD_DAYS = 16;

    private static volatile MedicationRepository instance;

    private final Context context;
    private final MedicationDao dao;
    private final RemoteMedicationStore remote;
    private final Executor io = Executors.newSingleThreadExecutor();
    private final Executor network = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private MedicationRepository(Context context) {
        this.context = context.getApplicationContext();
        this.dao = MedicationDatabase.get(this.context).dao();
        this.remote = new FirestoreMedicationStore();
    }

    public static MedicationRepository get(Context context) {
        if (instance == null) {
            synchronized (MedicationRepository.class) {
                if (instance == null) {
                    instance = new MedicationRepository(context);
                }
            }
        }
        return instance;
    }

    /** 복약 데이터의 주인. 없으면 null (로그아웃 상태 또는 카카오 사용자) */
    @Nullable
    public String currentOwner() {
        return remote.currentUid();
    }

    // 앱 수명

    /** Application에서 한 번 부른다. 계정이 바뀌면 이전 사용자의 알림과 기기 데이터를 지운다 */
    public void watchAccount() {
        remote.addAccountListener(() -> io.execute(this::reconcileAccount));
    }

    /** 앱을 열 때 예약을 점검하고 서버와 맞춘다 */
    public void onAppStarted() {
        checkReminders();
        requestSync();
    }

    /** 시간·시간대 변경, 재부팅, 화면 복귀 때 예약을 다시 맞춘다 */
    public void checkReminders() {
        io.execute(this::reschedule);
    }

    public void checkReminders(Runnable done) {
        io.execute(() -> {
            try {
                reschedule();
            } finally {
                done.run();
            }
        });
    }

    public void requestSync() {
        if (currentOwner() != null) {
            MedicationSyncWorker.enqueue(context);
        }
    }

    public void retryFailedSync() {
        String owner = currentOwner();
        if (owner == null) {
            return;
        }
        io.execute(() -> {
            dao.requeueFailed(owner);
            MedicationSyncWorker.enqueue(context);
        });
    }

    private void reconcileAccount() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String previous = prefs.getString(PREF_OWNER, null);
        String current = currentOwner();
        if (Objects.equals(previous, current)) {
            return;
        }
        if (previous != null) {
            MedicationSyncWorker.cancel(context);
            ReminderScheduler.cancelAll(context, dao);
            ReminderNotifier.cancelAll(context);
            dao.clearAll();
            Log.i(TAG, "계정 변경: 이전 사용자의 복약 데이터와 알림을 지움");
        }
        prefs.edit().putString(PREF_OWNER, current).apply();
        if (current != null) {
            requestSync();
        }
    }

    private void reschedule() {
        String owner = currentOwner();
        LocalDate today = LocalDate.now();
        ReminderScheduler.rescheduleAll(context, dao, owner,
                owner == null ? List.of() : dao.takenKeys(owner, today.toString(),
                        today.plusDays(TAKEN_LOOKAHEAD_DAYS).toString()));
    }

    // 조회

    public LiveData<List<MedicationSchedule>> observeSchedules(String owner) {
        return dao.observeSchedules(owner);
    }

    public LiveData<List<IntakeRecord>> observeIntakes(String owner, LocalDate from, LocalDate to) {
        return dao.observeIntakes(owner, from.toString(), to.toString());
    }

    public LiveData<Integer> observeSyncCount(String owner, int state) {
        return dao.observeSyncCount(owner, state);
    }

    public void loadSchedule(String scheduleId, Result<MedicationSchedule> result) {
        io.execute(() -> post(result, dao.schedule(scheduleId)));
    }

    /** 수정·삭제할 수 있는 최신 버전. 끝났거나 지운 일정이면 null */
    public void findEditable(String seriesId, Result<MedicationSchedule> result) {
        String owner = currentOwner();
        if (owner == null) {
            post(result, null);
            return;
        }
        io.execute(() -> post(result, ScheduleVersioning.editableVersion(
                dao.seriesVersions(owner, seriesId), LocalDate.now())));
    }

    public void loadDrugNames(Result<List<String>> result) {
        String owner = currentOwner();
        if (owner != null) {
            io.execute(() -> post(result, dao.drugNames(owner)));
        }
    }

    /** 화면이 보는 날짜 범위의 기록만 서버에서 받아 합친다. 실패해도 기기 데이터로 화면은 동작한다 */
    public void loadRange(LocalDate from, LocalDate to) {
        String owner = currentOwner();
        if (owner == null) {
            return;
        }
        network.execute(() -> {
            try {
                if (mergeIntakes(owner, from, to)) {
                    checkReminders();
                }
            } catch (RemoteStoreException e) {
                Log.w(TAG, "기록 조회 실패: " + e.getMessage());
            }
        });
    }

    // 일정 저장

    public void saveNew(ScheduleDraft draft, Result<Boolean> result) {
        String owner = currentOwner();
        if (owner == null) {
            post(result, false);
            return;
        }
        io.execute(() -> {
            dao.upsertSchedule(ScheduleVersioning.fromDraft(draft, UUID.randomUUID().toString(), owner,
                    ZoneId.systemDefault().getId(), System.currentTimeMillis()));
            afterChange();
            post(result, true);
        });
    }

    /** @param current 수정 화면을 열 때 불러온 버전 */
    public void update(MedicationSchedule current, ScheduleDraft draft, Result<Boolean> result) {
        io.execute(() -> {
            ScheduleVersioning.EditPlan plan = ScheduleVersioning.applyEdit(current, draft, LocalDate.now(),
                    System.currentTimeMillis(), ZoneId.systemDefault().getId(), UUID.randomUUID().toString());
            MedicationDatabase.get(context).runInTransaction(() -> {
                if (plan.closed != null) {
                    dao.upsertSchedule(plan.closed);
                }
                dao.upsertSchedule(plan.active);
            });
            ReminderNotifier.cancelSeries(context, current.seriesId);
            afterChange();
            post(result, true);
        });
    }

    public void delete(MedicationSchedule current, Result<Boolean> result) {
        io.execute(() -> {
            dao.upsertSchedule(ScheduleVersioning.applyDelete(current, LocalDate.now(), System.currentTimeMillis()));
            ReminderNotifier.cancelSeries(context, current.seriesId);
            afterChange();
            post(result, true);
        });
    }

    private void afterChange() {
        reschedule();
        MedicationSyncWorker.enqueue(context);
    }

    // 복용 기록

    /** 화면의 알약 버튼. 예정 시각 전이면 allowEarly=true로 다시 불러야 기록된다 */
    public void markTaken(DoseItem dose, boolean allowEarly, Result<MarkResult> result) {
        io.execute(() -> post(result, record(dose, allowEarly)));
    }

    public void cancelTaken(DoseItem dose, Result<Boolean> result) {
        io.execute(() -> {
            boolean cancelled = dao.recordCancelled(dose.occurrenceKey, System.currentTimeMillis());
            if (cancelled) {
                afterChange();
            }
            post(result, cancelled);
        });
    }

    /** 알림의 "복용 완료" 버튼. 화면과 같은 기록 처리를 쓴다 */
    public void markTakenFromNotification(String occurrenceKey, String scheduleId, String slotId, LocalDate date,
                                          Runnable done) {
        io.execute(() -> {
            try {
                MedicationSchedule schedule = ownedSchedule(scheduleId);
                TimeSlot slot = schedule == null ? null : findSlot(schedule, slotId);
                if (slot == null) {
                    ReminderNotifier.cancel(context, occurrenceKey);
                    return;
                }
                record(DayPlanner.fromSchedule(schedule, slot, date, null, LocalDateTime.now()), true);
            } finally {
                done.run();
            }
        });
    }

    private MarkResult record(DoseItem dose, boolean allowEarly) {
        String owner = currentOwner();
        if (owner == null) {
            return MarkResult.NOT_FOUND;
        }
        IntakePolicy.Decision decision = IntakePolicy.decide(dose.date, dose.time, LocalDateTime.now());
        if (decision == IntakePolicy.Decision.FUTURE_BLOCKED) {
            return MarkResult.FUTURE_BLOCKED;
        }
        if (decision == IntakePolicy.Decision.NEEDS_EARLY_CONFIRM && !allowEarly) {
            return MarkResult.NEEDS_EARLY_CONFIRM;
        }

        long now = System.currentTimeMillis();
        IntakeRecord record = new IntakeRecord();
        record.occurrenceKey = dose.occurrenceKey;
        record.ownerUid = owner;
        record.scheduleId = dose.scheduleId;
        record.seriesId = dose.seriesId;
        record.slotId = dose.slotId;
        record.scheduledDate = dose.date.toString();
        record.scheduledTime = dose.time.toString();
        record.timeZone = ZoneId.systemDefault().getId();
        record.drugName = dose.drugName;
        record.dosage = dose.dosage;
        record.status = IntakeRecord.STATUS_TAKEN;
        record.takenAt = now;
        record.updatedAt = now;
        record.syncState = SyncState.PENDING;

        boolean created = dao.recordTaken(record);
        // 이미 완료된 회차라도 다른 경로(화면·알림)에서 남은 알림은 지운다
        ReminderNotifier.cancel(context, dose.occurrenceKey);
        if (created) {
            afterChange();
            return MarkResult.RECORDED;
        }
        return MarkResult.ALREADY_TAKEN;
    }

    // 알람 수신

    /** 알람이 울렸을 때 알림을 띄울지 판단하고 다음 회차를 예약한다 */
    public void handleAlarm(String scheduleId, String slotId, LocalDate date, Runnable done) {
        io.execute(() -> {
            try {
                MedicationSchedule schedule = ownedSchedule(scheduleId);
                TimeSlot slot = schedule == null ? null : findSlot(schedule, slotId);
                if (slot != null && schedule.remindersEnabled && schedule.runsOn(date)) {
                    String key = Occurrences.key(schedule.seriesId, slotId, date);
                    IntakeRecord record = dao.intake(key);
                    boolean late = Duration.between(date.atTime(slot.localTime()), LocalDateTime.now())
                            .compareTo(LATE_ALARM_LIMIT) > 0;
                    if ((record == null || !record.isTaken()) && !late) {
                        ReminderNotifier.show(context, schedule, slot, date);
                    }
                }
                reschedule();
            } finally {
                done.run();
            }
        });
    }

    @Nullable
    private MedicationSchedule ownedSchedule(String scheduleId) {
        MedicationSchedule schedule = dao.schedule(scheduleId);
        return schedule != null && schedule.ownerUid.equals(currentOwner()) ? schedule : null;
    }

    @Nullable
    private static TimeSlot findSlot(MedicationSchedule schedule, String slotId) {
        for (TimeSlot slot : schedule.slots) {
            if (slot.slotId.equals(slotId)) {
                return slot;
            }
        }
        return null;
    }

    // 서버 동기화 (MedicationSyncWorker에서 부른다)

    /** @return 모두 처리했으면 true, 일시적 실패가 있어 다시 시도해야 하면 false */
    boolean syncBlocking() {
        String owner = currentOwner();
        if (owner == null) {
            return true;
        }
        boolean finished = pushSchedules(owner);
        finished &= pushIntakes(owner);
        if (!finished) {
            return false;
        }
        try {
            boolean changed = false;
            for (MedicationSchedule schedule : remote.fetchSchedules(owner)) {
                changed |= dao.mergeRemoteSchedule(schedule);
            }
            LocalDate today = LocalDate.now();
            changed |= mergeIntakes(owner, today.minusDays(PULL_PAST_DAYS), today.plusDays(1));
            if (changed) {
                checkReminders();
            }
            return true;
        } catch (RemoteStoreException e) {
            Log.w(TAG, "서버 조회 실패: " + e.getMessage());
            return !e.isTemporary();
        }
    }

    private boolean pushSchedules(String owner) {
        boolean finished = true;
        for (MedicationSchedule schedule : dao.pendingSchedules(owner)) {
            try {
                remote.putSchedule(schedule);
                dao.setScheduleSyncState(schedule.id, schedule.updatedAt, SyncState.SYNCED);
            } catch (RemoteStoreException e) {
                Log.w(TAG, "일정 저장 실패: " + e.getMessage());
                if (e.isTemporary()) {
                    finished = false;
                } else {
                    dao.setScheduleSyncState(schedule.id, schedule.updatedAt, SyncState.FAILED);
                }
            }
        }
        return finished;
    }

    private boolean pushIntakes(String owner) {
        boolean finished = true;
        for (IntakeRecord record : dao.pendingIntakes(owner)) {
            try {
                remote.putIntake(record);
                dao.setIntakeSyncState(record.occurrenceKey, record.updatedAt, SyncState.SYNCED);
            } catch (RemoteStoreException e) {
                Log.w(TAG, "기록 저장 실패: " + e.getMessage());
                if (e.isTemporary()) {
                    finished = false;
                } else {
                    dao.setIntakeSyncState(record.occurrenceKey, record.updatedAt, SyncState.FAILED);
                }
            }
        }
        return finished;
    }

    private boolean mergeIntakes(String owner, LocalDate from, LocalDate to) throws RemoteStoreException {
        boolean changed = false;
        for (IntakeRecord record : remote.fetchIntakes(owner, from.toString(), to.toString())) {
            changed |= dao.mergeRemoteIntake(record);
        }
        return changed;
    }

    private <T> void post(Result<T> result, T value) {
        main.post(() -> result.onResult(value));
    }
}
