package com.medimate.reminder;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;

import com.medimate.data.medication.MedicationDao;
import com.medimate.medication.MedicationSchedule;
import com.medimate.medication.Occurrences;
import com.medimate.medication.ScheduledAlarm;
import com.medimate.medication.TimeSlot;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 기기 알람 예약 (README 9-6). 시간 슬롯마다 다음 회차 하나만 일회성 알람으로 걸고,
 * 알람이 울리면 ReminderReceiver가 그다음 회차를 건다.
 * PendingIntent를 일정·슬롯별 주소로 구분해 같은 시각의 약도 서로 덮어쓰지 않는다.
 */
public final class ReminderScheduler {

    private static final String TAG = "ReminderScheduler";

    private ReminderScheduler() {
    }

    /** 걸려 있는 예약을 모두 취소하고 일정대로 다시 건다. 지난 회차는 걸지 않는다 */
    public static synchronized void rescheduleAll(Context context, MedicationDao dao, @Nullable String owner,
                                                  Collection<String> takenKeys) {
        cancelAll(context, dao);
        if (owner == null) {
            return;
        }
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        ZonedDateTime now = ZonedDateTime.now();
        Set<String> taken = new HashSet<>(takenKeys);
        int count = 0;
        for (MedicationSchedule schedule : dao.schedules(owner)) {
            if (schedule.deleted || !schedule.remindersEnabled) {
                continue;
            }
            for (TimeSlot slot : schedule.slots) {
                Occurrences.Next next = Occurrences.next(schedule, slot, now, taken);
                if (next == null) {
                    continue;
                }
                long triggerAt = next.at.toInstant().toEpochMilli();
                PendingIntent pending = pendingIntent(context,
                        ReminderReceiver.intent(context, schedule.id, slot.slotId, next.date.toString()));
                ScheduledAlarm alarm = new ScheduledAlarm();
                alarm.scheduleId = schedule.id;
                alarm.slotId = slot.slotId;
                alarm.occurrenceDate = next.date.toString();
                alarm.triggerAt = triggerAt;
                alarm.exact = set(alarmManager, triggerAt, pending);
                dao.insertAlarm(alarm);
                count++;
            }
        }
        Log.i(TAG, "알람 " + count + "개 예약, 정확한 알람 " + (canUseExact(alarmManager) ? "가능" : "불가"));
    }

    public static synchronized void cancelAll(Context context, MedicationDao dao) {
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        for (ScheduledAlarm alarm : dao.alarms()) {
            alarmManager.cancel(pendingIntent(context,
                    ReminderReceiver.intent(context, alarm.scheduleId, alarm.slotId, alarm.occurrenceDate)));
        }
        dao.clearAlarms();
    }

    static Uri alarmUri(String scheduleId, String slotId) {
        return new Uri.Builder().scheme("medimate-alarm").authority("dose")
                .appendPath(scheduleId).appendPath(slotId).build();
    }

    private static PendingIntent pendingIntent(Context context, Intent intent) {
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** @return 정확한 알람으로 걸었으면 true. 권한이 없으면 절전 중에도 울리되 늦을 수 있는 알람으로 건다 */
    private static boolean set(AlarmManager alarmManager, long triggerAt, PendingIntent pending) {
        if (canUseExact(alarmManager)) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
                return true;
            } catch (SecurityException e) {
                Log.w(TAG, "정확한 알람 권한이 없음");
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        return false;
    }

    static boolean canUseExact(AlarmManager alarmManager) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms();
    }
}
