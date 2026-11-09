package com.medimate.reminder;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.medimate.R;
import com.medimate.medication.MedicationSchedule;
import com.medimate.medication.Occurrences;
import com.medimate.medication.TimeSlot;
import com.medimate.ui.schedule.DoseText;
import com.medimate.ui.schedule.MedicationScheduleActivity;

import java.time.LocalDate;

/** 복약 알림 표시·제거. 회차 키를 태그로 써서 같은 시각의 약도 각각 알림으로 뜬다. */
public final class ReminderNotifier {

    private static final String CHANNEL_ID = "medication_reminder";
    private static final int NOTIFICATION_ID = 1;

    private ReminderNotifier() {
    }

    /** Application에서 부른다. 소리·진동은 지정하지 않아 사용자와 시스템 설정을 따른다 */
    public static void createChannel(Context context) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.reminder_channel_name), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.reminder_channel_desc));
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    public static void show(Context context, MedicationSchedule schedule, TimeSlot slot, LocalDate date) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        String key = Occurrences.key(schedule.seriesId, slot.slotId, date);
        String text = schedule.drugName + " · " + DoseText.time(slot.localTime())
                + " · 1회 " + schedule.dosage;

        // 누르면 그 날짜의 복약 화면만 연다. 복용 완료로 기록하지 않는다
        Intent open = MedicationScheduleActivity.newIntent(context, date)
                .setData(new Uri.Builder().scheme("medimate-dose").authority("open").appendPath(key).build());
        PendingIntent content = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent done = PendingIntent.getBroadcast(context, 0,
                ReminderActionReceiver.intent(context, key, schedule.id, slot.slotId, date.toString()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 잠금 화면에는 약 이름을 숨긴다 (복약 정보는 민감정보, README 9-5)
        NotificationCompat.Builder publicVersion = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_pill)
                .setContentTitle(context.getString(R.string.reminder_title));

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_pill)
                .setContentTitle(context.getString(R.string.reminder_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion.build())
                .setContentIntent(content)
                // 자동 닫기를 켜면 시스템이 묶은 알림 중 하나만 눌러도 같은 시각의 다른 약 알림까지 사라진다.
                // 복용 완료를 기록하거나 사용자가 직접 밀어서 닫을 때까지 남겨 둔다
                .setAutoCancel(false)
                .addAction(R.drawable.ic_check, context.getString(R.string.reminder_action_done), done);
        NotificationManagerCompat.from(context).notify(key, NOTIFICATION_ID, builder.build());
    }

    public static void cancel(Context context, String occurrenceKey) {
        NotificationManagerCompat.from(context).cancel(occurrenceKey, NOTIFICATION_ID);
    }

    /** 일정을 고치거나 지우면 그 일정의 떠 있는 알림을 지운다 */
    public static void cancelSeries(Context context, String seriesId) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        for (StatusBarNotification active : manager.getActiveNotifications()) {
            if (active.getTag() != null && active.getTag().startsWith(seriesId + "_")) {
                manager.cancel(active.getTag(), active.getId());
            }
        }
    }

    public static void cancelAll(Context context) {
        NotificationManagerCompat.from(context).cancelAll();
    }

    /** 알림이 시스템에서 꺼져 있는지 (권한 거부, 앱 알림 끄기, 채널 끄기) */
    static boolean isBlocked(Context context) {
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        if (!manager.areNotificationsEnabled()) {
            return true;
        }
        NotificationChannel channel = context.getSystemService(NotificationManager.class)
                .getNotificationChannel(CHANNEL_ID);
        return channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE;
    }
}
