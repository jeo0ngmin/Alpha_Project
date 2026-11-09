package com.medimate.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.medimate.data.medication.MedicationRepository;

import java.time.LocalDate;

/** 알람 수신: 알림을 띄우고 다음 회차를 예약한다. 인텐트에는 id만 싣고 약 정보는 기기 DB에서 읽는다. */
public class ReminderReceiver extends BroadcastReceiver {

    private static final String EXTRA_SCHEDULE_ID = "scheduleId";
    private static final String EXTRA_SLOT_ID = "slotId";
    private static final String EXTRA_DATE = "date";

    static Intent intent(Context context, String scheduleId, String slotId, String date) {
        return new Intent(context, ReminderReceiver.class)
                .setData(ReminderScheduler.alarmUri(scheduleId, slotId))
                .putExtra(EXTRA_SCHEDULE_ID, scheduleId)
                .putExtra(EXTRA_SLOT_ID, slotId)
                .putExtra(EXTRA_DATE, date);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        MedicationRepository.get(context).handleAlarm(
                intent.getStringExtra(EXTRA_SCHEDULE_ID),
                intent.getStringExtra(EXTRA_SLOT_ID),
                LocalDate.parse(intent.getStringExtra(EXTRA_DATE)),
                pending::finish);
    }
}
