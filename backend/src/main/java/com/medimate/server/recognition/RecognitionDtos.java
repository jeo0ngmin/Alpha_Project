package com.medimate.server.recognition;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.medimate.server.common.CurrentMedications;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class RecognitionDtos {

    private RecognitionDtos() {
    }

    /** ocrRawText를 주면 서버는 CLOVA OCR을 부르지 않는다. ocrBlocks는 받기만 하고 쓰지 않는다. */
    public record RecognitionRequest(@NotBlank String fileId, @Size(max = 20000) String ocrRawText, JsonNode ocrBlocks,
                                     LocalDate hintDispensedDate) {
    }

    /**
     * 약봉투에서 읽은 약 하나. 추론 서버 결과에서 만들어지고, 사용자가 고치면 바뀐다.
     * needsReview가 true인 약이 남아 있으면 확정할 수 없다.
     */
    public static class BagItem {
        public String itemId;
        public String rawText;
        public String refinedName;
        public boolean matched;
        public String itemSeq;
        public String itemName;
        public String entpName;
        public String dosageForm;
        public String strength;
        public Double dosageAmount;
        public String dosageUnit;
        public Integer frequencyPerDay;
        public List<String> timeSlots;
        public boolean slotsInferred;
        public String mealRelation;
        public Integer mealOffsetMinutes;
        public Integer durationDays;
        public Double totalQuantity;
        public String instructions;
        /** 봉투에 적힌 이 약의 주의사항 (문서에 있는 문장만). */
        public List<String> cautions;
        /** 화면용 한 줄 요약: "1회 1정 · 하루 3회 · 아침, 점심, 저녁 · 식후 30분 · 5일분". */
        public String displayText;
        public double confidence;
        public boolean needsReview;
        public List<String> reviewReasons;
        public List<JsonNode> candidates;
        public boolean excluded;
        public String ttsScript;
        /**
         * 약 상세 정보 화면용: 약 설명, 주의사항, 복용방법, 보관방법(+ 있으면 함께 먹으면 안 되는 것, 부작용).
         * 식약처 e약은요 기준이며, 품목과 연결되지 않은 약은 안내 문장만 있다.
         */
        public List<com.medimate.server.drug.DrugInfoService.Section> sections;
        /** FOUND | NOT_IN_MFDS | UNAVAILABLE | NOT_CONFIGURED | NOT_MATCHED */
        public String infoStatus;

        /** 알림과 음성에 쓸 이름: 식약처 품목명, 없으면 봉투에 적힌 이름을 고친 것. */
        public String displayName() {
            if (itemName != null) {
                return itemName;
            }
            return refinedName != null ? refinedName : rawText;
        }
    }

    public record Source(String pharmacyName, String hospitalName, String dispensedDate, String patientNameMasked) {
    }

    public record JobError(String code, String message, String ttsMessage, boolean retryable) {
    }

    public record RecognitionJob(String jobId, String type, String status, Double overallConfidence,
                                 OffsetDateTime processedAt, Long processingMs, Source source, List<BagItem> items,
                                 int reviewRequiredCount, String summaryTtsScript, JobError error,
                                 List<String> generalInstructions, List<String> generalCautions) {
    }

    /** 확정 전에만 고칠 수 있다. 어떤 값이든 고치면 "사용자가 확인한 약"이 된다(빈 본문도 확인으로 본다). */
    public record ItemPatch(String itemSeq, @DecimalMin("0.25") @DecimalMax("20") Double dosageAmount,
                            @Min(1) @Max(6) Integer frequencyPerDay, List<String> timeSlots, String mealRelation,
                            @Min(1) @Max(365) Integer durationDays, Boolean excluded) {
    }

    /**
     * timeSlotOverrides: {"MORNING": "07:30"}처럼 시점별 알림 시각을 바꾼다.
     * acceptAll: 약마다 확인 없이 확정한다(확인 단계가 없는 화면용). 복용량·횟수·일수를 읽지 못한 약과
     * 이미 복용 중인 약은 일정에서 빼고 skippedItems로 알려 준다.
     * currentMedications: 앱(Firestore)에 저장된 "복용 중" 약. 서버는 일정을 보관하지 않으므로 이미 먹는 약인지는
     * 이 목록으로만 판단한다. 보내지 않으면 확인하지 않는다.
     */
    public record ConfirmRequest(LocalDate startDate, List<String> excludeItemIds, Map<String, String> timeSlotOverrides,
                                 Boolean acceptAll, @Valid List<CurrentMedications.Entry> currentMedications) {
    }

    public record SkippedItem(String itemId, String name, String reason) {
    }

    public record TimeSlotEntry(String slot, String time) {
    }

    /** 복약 일정 초안. 서버는 저장하지 않는다. 앱이 이것으로 일정을 Firestore에 저장하고 알람을 만든다. */
    public record PlanDraft(String itemSeq, String customName, Double dosageAmount, String dosageUnit,
                            String frequencyType, Integer frequencyPerDay, List<TimeSlotEntry> timeSlots,
                            String mealRelation, Integer mealOffsetMinutes, LocalDate startDate, LocalDate endDate,
                            Integer durationDays, Double remainingQuantity, boolean notificationEnabled, String memo) {
    }

    /** 화면용 요약: plans와 같은 순서. itemId로 결과 목록의 약과 연결한다. generatedDoseCount는 남은 복용 횟수. */
    public record PlanSummary(String itemId, String itemSeq, String itemName, LocalDate startDate, LocalDate endDate,
                              int generatedDoseCount) {
    }

    /** 확정 결과. 서버에는 아무것도 저장되지 않는다. plans를 앱이 저장한다. */
    public record ConfirmResult(String jobId, String status, List<PlanDraft> plans, List<PlanSummary> summaries,
                                int totalDoseCount, OffsetDateTime firstDoseAt, List<JsonNode> interactionWarnings,
                                String alarmQuestion, String ttsScript, List<SkippedItem> skippedItems) {
    }
}
