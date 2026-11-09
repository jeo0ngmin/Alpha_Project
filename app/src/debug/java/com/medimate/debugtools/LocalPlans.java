package com.medimate.debugtools;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.medimate.plan.model.CurrentMedication;

/**
 * [시험용·디버그 전용] 복약 일정을 기기 안(SharedPreferences)에 저장한다.
 * 실제 앱에서는 화면 담당 팀원의 Room + Firestore 저장소가 이 역할을 한다. 서버는 일정을 보관하지 않는다.
 * 저장 모양은 서버의 일정 초안(PlanDraft)에 planId·itemName·status와 화면용 문장을 붙인 것이다.
 */
public final class LocalPlans {

    private static final String PREFS = "medimate-debug-plans";
    private static final String KEY = "plans";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private LocalPlans() {
    }

    public static synchronized JsonArray all(Context context) {
        String raw = prefs(context).getString(KEY, "[]");
        try {
            return JsonParser.parseString(raw).getAsJsonArray();
        } catch (RuntimeException e) {
            return new JsonArray();
        }
    }

    /** 서버 요청(알약 식별, 약봉투 확정)에 보낼 "복용 중" 목록: ACTIVE이고 아직 끝나지 않은 일정. */
    public static synchronized List<CurrentMedication> taking(Context context) {
        List<CurrentMedication> out = new ArrayList<>();
        LocalDate today = LocalDate.now(KST);
        for (JsonElement e : all(context)) {
            JsonObject p = e.getAsJsonObject();
            String end = Ui.str(p, "endDate");
            if ("ACTIVE".equals(Ui.str(p, "status")) && (end == null || !LocalDate.parse(end).isBefore(today))) {
                out.add(new CurrentMedication(Ui.str(p, "itemSeq"), Ui.str(p, "itemName")));
            }
        }
        return out;
    }

    /**
     * 약봉투 확정 결과(plans와 summaries)를 저장하고 저장한 일정을 돌려준다.
     * notify: "알람으로 설정할까요?"에 대한 답.
     */
    public static synchronized List<JsonObject> addConfirmed(Context context, JsonObject result, boolean notify) {
        JsonArray plans = Ui.arr(result, "plans");
        JsonArray summaries = Ui.arr(result, "summaries");
        List<JsonObject> added = new ArrayList<>();
        JsonArray stored = all(context);
        for (int i = 0; i < plans.size(); i++) {
            JsonObject draft = plans.get(i).getAsJsonObject();
            JsonObject summary = i < summaries.size() ? summaries.get(i).getAsJsonObject() : new JsonObject();
            String name = Ui.str(summary, "itemName") != null ? Ui.str(summary, "itemName") : Ui.str(draft, "customName");
            added.add(store(stored, draft, name, notify));
        }
        save(context, stored);
        return added;
    }

    /** 알약 선택 결과의 draftPlan으로 "내 약"에 추가한다. */
    public static synchronized JsonObject addDraft(Context context, JsonObject draft, String itemName) {
        JsonArray stored = all(context);
        JsonObject plan = store(stored, draft, itemName, Ui.bool(draft, "notificationEnabled"));
        save(context, stored);
        return plan;
    }

    public static synchronized JsonObject setNotify(Context context, long planId, boolean on) {
        JsonArray stored = all(context);
        JsonObject found = find(stored, planId);
        if (found != null) {
            found.addProperty("notificationEnabled", on);
            save(context, stored);
        }
        return found;
    }

    /** 복용 종료: 기록은 남기고 status만 STOPPED로. */
    public static synchronized void stop(Context context, long planId) {
        JsonArray stored = all(context);
        JsonObject found = find(stored, planId);
        if (found != null) {
            found.addProperty("status", "STOPPED");
            save(context, stored);
        }
    }

    public static synchronized void purge(Context context, long planId) {
        JsonArray stored = all(context);
        JsonArray kept = new JsonArray();
        for (JsonElement e : stored) {
            if (e.getAsJsonObject().get("planId").getAsLong() != planId) {
                kept.add(e);
            }
        }
        save(context, kept);
    }

    private static JsonObject store(JsonArray stored, JsonObject draft, String itemName, boolean notify) {
        JsonObject plan = draft.deepCopy();
        plan.addProperty("planId", nextId(stored));
        plan.addProperty("itemName", itemName == null ? "이름 없는 약" : itemName);
        plan.addProperty("status", "ACTIVE");
        plan.addProperty("notificationEnabled", notify);
        plan.addProperty("dosageText", dosageText(draft));
        plan.addProperty("scheduleText", scheduleText(draft));
        stored.add(plan);
        return plan;
    }

    private static long nextId(JsonArray stored) {
        long max = 0;
        for (JsonElement e : stored) {
            max = Math.max(max, e.getAsJsonObject().get("planId").getAsLong());
        }
        return max + 1;
    }

    private static JsonObject find(JsonArray stored, long planId) {
        for (JsonElement e : stored) {
            if (e.getAsJsonObject().get("planId").getAsLong() == planId) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    /** "1회 1정" */
    private static String dosageText(JsonObject d) {
        double amount = d.has("dosageAmount") && !d.get("dosageAmount").isJsonNull() ? d.get("dosageAmount").getAsDouble() : 1;
        String n = amount == Math.floor(amount) ? String.valueOf((long) amount) : String.valueOf(amount);
        String unit = Ui.str(d, "dosageUnit");
        String label = "CAPSULE".equals(unit) ? "캡슐" : "PACK".equals(unit) ? "포" : "ML".equals(unit) ? "mL" : "정";
        return "1회 " + n + label;
    }

    /** "08:00 · 12:30 · 18:30" */
    private static String scheduleText(JsonObject d) {
        List<String> times = new ArrayList<>();
        for (JsonElement e : Ui.arr(d, "timeSlots")) {
            times.add(Ui.str(e.getAsJsonObject(), "time"));
        }
        return String.join(" · ", times);
    }

    private static void save(Context context, JsonArray plans) {
        prefs(context).edit().putString(KEY, plans.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
