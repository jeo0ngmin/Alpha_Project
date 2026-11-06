package com.medimate.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.medimate.data.medication.MedicationRepository;

/**
 * 재부팅·앱 업데이트·시간과 시간대 변경·정확한 알람 권한 변경 때 저장된 일정으로 알람을 다시 건다.
 * 앞으로 올 회차만 걸기 때문에 놓친 알림이 한꺼번에 울리지 않는다.
 */
public class SystemEventReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        MedicationRepository.get(context).checkReminders(pending::finish);
    }
}
