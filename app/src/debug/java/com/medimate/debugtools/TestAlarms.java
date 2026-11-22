package com.medimate.debugtools;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * [시험용·디버그 전용] 저장된 복약 일정으로 기기 알람을 만든다. 실제 알람은 화면 담당 팀원이 구현한다.
 * 일정마다 앞으로 울릴 시각을 최대 MAX_PER_PLAN개까지 한꺼번에 등록하는 단순한 방식이다.
 */
public final class TestAlarms {

    static final String CHANNEL = "medimate-test-dose";
    private static final int MAX_PER_PLAN = 60;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private TestAlarms() {
    }

    /** 기기에 저장한 일정(LocalPlans)으로 알람을 만든다. 만든 개수를 돌려준다. */
    public static int schedule(Context context, JsonObject plan) {
        long planId = plan.get("planId").getAsLong();
        cancel(context, planId);
        if (!plan.has("notificationEnabled") || !plan.get("notificationEnabled").getAsBoolean()
                || !"ACTIVE".equals(text(plan, "status"))) {
            return 0;
        }
        String name = text(plan, "nickname") != null ? text(plan, "nickname") : text(plan, "itemName");
        String body = text(plan, "dosageText") + " · " + text(plan, "scheduleText");
        LocalDate start = LocalDate.parse(text(plan, "startDate"));
        LocalDate end = text(plan, "endDate") == null ? LocalDate.now(KST).plusDays(30) : LocalDate.parse(text(plan, "endDate"));
        String type = text(plan, "frequencyType");
        int interval = plan.has("intervalDays") && !plan.get("intervalDays").isJsonNull() ? plan.get("intervalDays").getAsInt() : 1;
        JsonArray days = plan.has("daysOfWeek") && plan.get("daysOfWeek").isJsonArray() ? plan.getAsJsonArray("daysOfWeek") : new JsonArray();
        JsonArray slots = plan.getAsJsonArray("timeSlots");
        LocalDateTime now = LocalDateTime.now(KST);
        int index = 0;
        for (LocalDate d = start.isBefore(now.toLocalDate()) ? now.toLocalDate() : start; !d.isAfter(end) && index < MAX_PER_PLAN;
             d = d.plusDays(1)) {
            if ("INTERVAL_DAYS".equals(type) && ChronoUnit.DAYS.between(start, d) % interval != 0) {
                continue;
            }
            if ("WEEKLY".equals(type) && !containsDay(days, d)) {
                continue;
            }
            if ("AS_NEEDED".equals(type)) {
                break;
            }
            for (JsonElement e : slots) {
                LocalDateTime at = d.atTime(LocalTime.parse(e.getAsJsonObject().get("time").getAsString()));
                if (at.isBefore(now) || index >= MAX_PER_PLAN) {
                    continue;
                }
                set(context, planId, index++, at.atZone(KST).toInstant().toEpochMilli(), name + " 드실 시간입니다", body);
            }
        }
        return index;
    }

    private static boolean containsDay(JsonArray days, LocalDate d) {
        String name = d.getDayOfWeek().name().substring(0, 3);
        for (JsonElement e : days) {
            if (name.equals(e.getAsString())) {
                return true;
            }
        }
        return false;
    }

    /** 바로 확인할 수 있도록 1분 뒤에 한 번 울린다. */
    public static void testInOneMinute(Context context) {
        set(context, 0, 999, System.currentTimeMillis() + 60_000, "시험 알림", "1분 뒤 알림이 정상으로 울렸습니다.");
    }

    public static void cancel(Context context, long planId) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        for (int i = 0; i < MAX_PER_PLAN; i++) {
            PendingIntent p = PendingIntent.getBroadcast(context, requestCode(planId, i), new Intent(context, Receiver.class),
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (p != null) {
                alarms.cancel(p);
                p.cancel();
            }
        }
    }

    private static int requestCode(long planId, int index) {
        return (int) (planId * 1000 + index);
    }

    private static void set(Context context, long planId, int index, long at, String title, String body) {
        Intent intent = new Intent(context, Receiver.class).putExtra("title", title).putExtra("body", body)
                .putExtra("id", requestCode(planId, index));
        PendingIntent p = PendingIntent.getBroadcast(context, requestCode(planId, index), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms();
        if (exact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p); // 몇 분 늦을 수 있다
        }
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    static void ensureChannel(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "복약 알림(시험)", NotificationManager.IMPORTANCE_HIGH));
    }

    /** 알람이 울리면 알림을 띄운다. */
    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            ensureChannel(context);
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            nm.notify(intent.getIntExtra("id", 1), new NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle(intent.getStringExtra("title"))
                    .setContentText(intent.getStringExtra("body"))
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(intent.getStringExtra("body")))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build());
        }
    }
}
