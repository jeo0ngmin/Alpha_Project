package com.medimate.pill.model;

import java.util.List;

/** POST /pill-identification/{jobId}/select 응답: 약 정보 + 복용 계획 초안. */
public class SelectResult {
    public MedicationDetail medication;
    public DraftPlan draftPlan;

    public static class MedicationDetail extends MedicationSummary {
        public String itemPermitDate;
        public PillFeatures appearance;
    }

    public static class DraftPlan {
        public String itemSeq;
        public double dosageAmount;
        public String dosageUnit;
        public String frequencyType;
        public int frequencyPerDay;
        public List<TimeSlotEntry> timeSlots;
        public String mealRelation;
        public String startDate;
        public boolean notificationEnabled;
    }

    public static class TimeSlotEntry {
        public String slot;
        public String time;
    }
}
