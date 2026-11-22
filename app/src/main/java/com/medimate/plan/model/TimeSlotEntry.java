package com.medimate.plan.model;

/** 복용 시점과 알림 시각. slot: MORNING | LUNCH | DINNER | BEDTIME | CUSTOM, time: "08:00" */
public class TimeSlotEntry {
    public String slot;
    public String time;

    public TimeSlotEntry() {
    }

    public TimeSlotEntry(String slot, String time) {
        this.slot = slot;
        this.time = time;
    }
}
