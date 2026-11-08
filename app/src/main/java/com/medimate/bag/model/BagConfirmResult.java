package com.medimate.bag.model;

import java.util.Collections;
import java.util.List;

import com.google.gson.JsonObject;
import com.medimate.plan.model.PlanDraft;

/**
 * 확정 결과: 약마다 복약 일정 초안. 서버에는 아무것도 저장되지 않는다.
 * 앱이 plans를 Firestore에 저장하고, alarmQuestion에 대한 답으로 알람을 켤지 정한다.
 */
public class BagConfirmResult {
    public String jobId;
    public String status;
    /** 일정 초안. 약마다 하나, summaries와 같은 순서. */
    public List<PlanDraft> plans;
    /** 화면용 요약. plans와 같은 순서. */
    public List<PlanSummary> summaries;
    public int totalDoseCount;
    public String firstDoseAt;
    public List<JsonObject> interactionWarnings;
    /** "알람으로 설정할까요?" */
    public String alarmQuestion;
    public String ttsScript;
    /** 일정으로 만들지 못한 약과 이유 (acceptAll일 때) */
    public List<SkippedItem> skippedItems;

    public List<PlanDraft> plansOrEmpty() {
        return plans == null ? Collections.emptyList() : plans;
    }

    public List<PlanSummary> summariesOrEmpty() {
        return summaries == null ? Collections.emptyList() : summaries;
    }

    public List<SkippedItem> skippedItemsOrEmpty() {
        return skippedItems == null ? Collections.emptyList() : skippedItems;
    }

    public static class PlanSummary {
        /** 결과 목록(BagJob.items)의 약 */
        public String itemId;
        public String itemSeq;
        public String itemName;
        public String startDate;
        public String endDate;
        /** 지금 이후 알림이 울릴 횟수 */
        public int generatedDoseCount;
    }

    public static class SkippedItem {
        public String itemId;
        public String name;
        public String reason;
    }
}
