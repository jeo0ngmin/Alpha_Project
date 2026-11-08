package com.medimate.server.pill;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.medimate.server.common.Enums.PillJobStatus;
import com.medimate.server.pill.PillDtos.Candidate;
import com.medimate.server.pill.PillDtos.JobView;
import com.medimate.server.speech.SpeechFormatter;

/**
 * 추론 서버 응답을 앱용 결과로 바꾼다.
 * 사진만으로는 약을 확정할 수 없으므로 어떤 경우에도 "이 약입니다"라고 말하지 않는다. 항상 사용자가 확인하게 한다.
 */
final class PillResults {

    static final String RETAKE = "RETAKE";
    static final String MANUAL_SEARCH = "MANUAL_SEARCH";
    /** 사진 식별은 확신할 때도 틀릴 수 있다. 화면을 보지 못하는 사용자에게 "직접 확인"을 요구하지 않는다. */
    static final String CHECK_WITH_SOMEONE = "사진만으로는 틀릴 수 있으니, 드시기 전에 약 봉투나 약사에게 꼭 확인해 주세요.";
    /** 1·2위 점수 차이가 이보다 작으면 사용자가 골라야 한다 (API 명세). */
    private static final double SELECTION_MARGIN = 0.15;

    private PillResults() {
    }

    static JobView map(String jobId, JsonNode r) {
        String status = r.path("status").asText();
        if ("NOT_FOUND".equals(status)) {
            String script = "MULTIPLE_PILLS".equals(r.path("reason").asText())
                    ? "알약이 여러 개 보입니다. 한 개만 놓고 다시 찍어 주세요."
                    : "사진에서 알약을 찾지 못했습니다. 알약 하나를 단색 바탕에 놓고 다시 찍어 주세요.";
            return new JobView(jobId, PillJobStatus.NOT_FOUND, null, null, List.of(), false, RETAKE, script);
        }
        if (!"COMPLETED".equals(status)) {
            throw new PillClient.Unavailable("알 수 없는 식별 상태");
        }
        JsonNode features = r.hasNonNull("features") ? r.get("features") : null;
        List<Candidate> candidates = candidates(r.path("candidates"));
        if (candidates.isEmpty()) {
            return new JobView(jobId, PillJobStatus.NOT_FOUND, features, null, List.of(), false, MANUAL_SEARCH,
                    "비슷한 약을 찾지 못했습니다. 모양과 색으로 직접 찾아 주세요.");
        }
        boolean low = r.path("lowConfidence").asBoolean(true);
        boolean close = candidates.size() > 1
                && candidates.get(0).matchScore() - candidates.get(1).matchScore() < SELECTION_MARGIN;
        String fallback = null;
        if (low) {
            fallback = r.path("imageIssues").isEmpty() ? MANUAL_SEARCH : RETAKE;
        }
        // 확신이 없을 때는 1위를 대표 결과로 내세우지 않는다
        Candidate top = low ? null : candidates.get(0);
        return new JobView(jobId, PillJobStatus.COMPLETED, features, top, candidates, low || close, fallback,
                script(candidates, low, close, fallback));
    }

    static JobView failed(String jobId) {
        return new JobView(jobId, PillJobStatus.FAILED, null, null, List.of(), false, MANUAL_SEARCH,
                "지금은 사진으로 약을 찾을 수 없습니다. 모양과 색으로 직접 찾아 주세요.");
    }

    private static List<Candidate> candidates(JsonNode rows) {
        List<Candidate> out = new ArrayList<>();
        for (JsonNode c : rows) {
            double score = Math.max(0, Math.min(1, c.path("score").asDouble()));
            out.add(new Candidate(out.size() + 1, c.path("itemSeq").asText(), c.path("itemName").asText(),
                    text(c, "entpName"), text(c, "className"), text(c, "etcOtcName"), text(c, "itemImageUrl"), score,
                    false));
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static String script(List<Candidate> candidates, boolean low, boolean close, String fallback) {
        String first = speakName(candidates.get(0).itemName());
        if (low) {
            String next = RETAKE.equals(fallback) ? "다시 찍어 보시거나, " : "모양과 색으로 직접 찾아 보시거나, ";
            return "사진만으로는 확실하지 않습니다. 가장 비슷해 보이는 약은 " + first + "입니다. " + next + "약사에게 확인해 주세요.";
        }
        if (close) {
            return "비슷한 약이 " + SpeechFormatter.nativeCount(candidates.size()) + " 개 있습니다. 가장 비슷한 약은 " + first
                    + "입니다. " + CHECK_WITH_SOMEONE;
        }
        return "가장 비슷한 약은 " + first + "입니다. " + CHECK_WITH_SOMEONE;
    }

    /** 괄호 안 성분명까지 읽으면 길어지므로 이름만 읽는다. */
    static String speakName(String itemName) {
        return SpeechFormatter.normalize(itemName.replaceAll("\\([^)]*\\)", "").trim());
    }
}
