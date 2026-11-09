package com.medimate.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.medimate.data.medication.MedicationRepository;

import java.time.LocalDate;

/** 알림의 "복용 완료" 버튼. 이 버튼을 눌렀을 때만 기록하며, 알림을 닫거나 눌렀을 때는 기록하지 않는다. */
public class ReminderActionReceiver extends BroadcastReceiver {

    private static final String EXTRA_KEY = "occurrenceKey";
    private static final String EXTRA_SCHEDULE_ID = "scheduleId";
    private static final String EXTRA_SLOT_ID = "slotId";
    private static final String EXTRA_DATE = "date";

    static Intent intent(Context context, String occurrenceKey, String scheduleId, String slotId, String date) {
        return new Intent(context, ReminderActionReceiver.class)
                .setData(new Uri.Builder().scheme("medimate-dose").authority("done")
                        .appendPath(occurrenceKey).build())
                .putExtra(EXTRA_KEY, occurrenceKey)
                .putExtra(EXTRA_SCHEDULE_ID, scheduleId)
                .putExtra(EXTRA_SLOT_ID, slotId)
                .putExtra(EXTRA_DATE, date);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        MedicationRepository.get(context).markTakenFromNotification(
                intent.getStringExtra(EXTRA_KEY),
                intent.getStringExtra(EXTRA_SCHEDULE_ID),
                intent.getStringExtra(EXTRA_SLOT_ID),
                LocalDate.parse(intent.getStringExtra(EXTRA_DATE)),
                pending::finish);
    }
}
