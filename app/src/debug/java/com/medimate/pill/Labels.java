package com.medimate.pill;

import java.util.ArrayList;
import java.util.List;

import com.medimate.pill.model.SelectResult;

/** 서버 enum 값 → 화면·음성용 한글. */
public final class Labels {

    private Labels() {
    }

    public static String timeSlot(String slot) {
        if (slot == null) {
            return "";
        }
        switch (slot) {
            case "MORNING": return "아침";
            case "LUNCH": return "점심";
            case "DINNER": return "저녁";
            case "BEDTIME": return "자기 전";
            default: return "지정 시간";
        }
    }

    public static String mealRelation(String relation) {
        if (relation == null) {
            return "";
        }
        switch (relation) {
            case "BEFORE_MEAL": return "식전";
            case "AFTER_MEAL": return "식후";
            case "WITH_MEAL": return "식사 중";
            case "EMPTY_STOMACH": return "공복";
            default: return "아무 때나";
        }
    }

    public static String dosageUnit(String unit) {
        if ("CAPSULE".equals(unit)) {
            return "캡슐";
        }
        if ("TABLET".equals(unit)) {
            return "정";
        }
        return "";
    }

    /** 목적격 조사: 마지막 글자에 받침이 있으면 '을', 없으면 '를'. 한글이 아니면 '을(를)'. */
    public static String objectParticle(String word) {
        if (word == null || word.isEmpty()) {
            return "을(를)";
        }
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return "을(를)";
        }
        return (last - 0xAC00) % 28 == 0 ? "를" : "을";
    }

    /** 예: "하루 1번, 아침 식후 1정" */
    public static String draftPlan(SelectResult.DraftPlan plan) {
        if (plan == null) {
            return "";
        }
        List<String> slots = new ArrayList<>();
        if (plan.timeSlots != null) {
            for (SelectResult.TimeSlotEntry t : plan.timeSlots) {
                slots.add(timeSlot(t.slot));
            }
        }
        String amount = plan.dosageAmount == Math.floor(plan.dosageAmount)
                ? String.valueOf((long) plan.dosageAmount) : String.valueOf(plan.dosageAmount);
        return "하루 " + plan.frequencyPerDay + "번, " + String.join("·", slots) + " "
                + mealRelation(plan.mealRelation) + " " + amount + dosageUnit(plan.dosageUnit);
    }
}
