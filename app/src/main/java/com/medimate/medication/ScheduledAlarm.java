package com.medimate.medication;

import androidx.annotation.NonNull;
import androidx.room.Entity;

/** 지금 AlarmManager에 걸어 둔 알람. 일정을 고치거나 로그아웃할 때 이전 예약을 빠짐없이 취소하려고 기록한다. */
@Entity(tableName = "scheduled_alarms", primaryKeys = {"scheduleId", "slotId"})
public class ScheduledAlarm {

    @NonNull
    public String scheduleId = "";
    @NonNull
    public String slotId = "";
    @NonNull
    public String occurrenceDate = "";
    public long triggerAt;
    public boolean exact;
}
