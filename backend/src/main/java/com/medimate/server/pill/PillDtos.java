package com.medimate.server.pill;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.medimate.server.common.CurrentMedications;
import com.medimate.server.common.Enums.ColorClass;
import com.medimate.server.common.Enums.DrugShape;
import com.medimate.server.common.Enums.PillJobStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class PillDtos {

    private PillDtos() {
    }

    public record PillHints(DrugShape drugShape, List<ColorClass> colorClass, @Size(max = 30) String printText) {
    }

    /** fileId는 앞면이다. frontFileId를 따로 주면 그쪽을 쓴다. */
    /** currentMedications: 앱이 저장한 "복용 중" 약. 후보에 isInMyMedications를 표시하는 데만 쓴다(선택). */
    public record IdentificationRequest(@NotBlank String fileId, String frontFileId, String backFileId,
                                        @Valid PillHints hints,
                                        @Valid List<CurrentMedications.Entry> currentMedications) {
    }

    public record JobAccepted(String jobId, String type, PillJobStatus status, int estimatedSeconds, int pollAfterMs,
                              String ttsMessage) {
    }

    /** isInMyMedications: 식별 요청 때 앱이 보낸 currentMedications에 있는 약인지. */
    public record Candidate(int rank, String itemSeq, String itemName, String entpName, String className,
                            String etcOtcName, String itemImageUrl, double matchScore, boolean isInMyMedications) {
    }

    public record JobView(String jobId, PillJobStatus status, JsonNode extractedFeatures, Candidate topMatch,
                          List<Candidate> candidates, boolean requiresUserSelection, String fallbackAction,
                          String ttsScript) {

        static JobView processing(String jobId) {
            return new JobView(jobId, PillJobStatus.PROCESSING, null, null, null, false, null, null);
        }

        JobView withStatus(PillJobStatus next) {
            return new JobView(jobId, next, extractedFeatures, topMatch, candidates, requiresUserSelection,
                    fallbackAction, ttsScript);
        }
    }

    public record SelectRequest(@NotBlank String itemSeq) {
    }

    public record TimeSlotEntry(String slot, String time) {
    }

    /** 처방이 아니라 앱 화면을 채우는 기본값이다. 실제 복용법은 약사·의사 안내를 따른다. */
    public record DraftPlan(String itemSeq, double dosageAmount, String dosageUnit, String frequencyType,
                            int frequencyPerDay, List<TimeSlotEntry> timeSlots, String mealRelation, String startDate,
                            boolean notificationEnabled) {
    }

    public record SelectResult(JsonNode medication, DraftPlan draftPlan) {
    }

    public record Source(String provider, String dataset, OffsetDateTime fetchedAt) {
    }
}
