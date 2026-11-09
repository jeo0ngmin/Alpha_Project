package com.medimate.reminder;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/** 시스템 설정 때문에 알림이 제한되는 상태. 화면은 정확한 알림이 예약된 것처럼 표시하지 않고 이 값을 안내한다. */
public enum ReminderStatus {
    OK,
    /** 알림 권한 거부 또는 앱·채널 알림 꺼짐 */
    NOTIFICATIONS_OFF,
    /** 정확한 알람 권한 없음. 알림은 오지만 늦을 수 있다 */
    EXACT_ALARM_NEEDED;

    public static ReminderStatus current(Context context) {
        if (ReminderNotifier.isBlocked(context)) {
            return NOTIFICATIONS_OFF;
        }
        return ReminderScheduler.canUseExact(context.getSystemService(AlarmManager.class))
                ? OK : EXACT_ALARM_NEEDED;
    }

    /** 이 상태를 해결할 설정 화면 */
    public Intent settingsIntent(Context context) {
        if (this == EXACT_ALARM_NEEDED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + context.getPackageName()));
        }
        return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
    }
}
