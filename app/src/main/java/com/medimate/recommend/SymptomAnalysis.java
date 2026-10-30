package com.medimate.recommend;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/** 증상 파악 결과 (README 8-6). 고민 이름은 규칙 표에 있는 것만 담긴다. */
public class SymptomAnalysis {

    @SerializedName("concerns")
    List<String> concerns;

    @SerializedName("needs_more_info")
    boolean needsMoreInfo;

    @SerializedName("follow_up_question")
    String followUpQuestion;

    @SerializedName("unmatched")
    boolean unmatched;

    @SerializedName("symptom_summary")
    String symptomSummary;

    @SerializedName("conditions")
    List<String> conditions;

    @SerializedName("medications")
    List<String> medications;

    @SerializedName("warnings")
    List<String> warnings;

    @SerializedName("triage")
    List<String> triage;

    @SerializedName("links")
    List<Link> links;

    /**
     * 사용자가 말한 약·지병·식사·예정 검사 하나와 근거 규칙 대상 id의 연결 (README 8-8).
     * 성분을 알 수 없으면 UNCLEAR, 성분은 알지만 규칙 대상이 아니면 NOT_LISTED.
     */
    public static class Link {
        public static final String UNCLEAR = "unclear";
        public static final String NOT_LISTED = "not_listed";

        @SerializedName("raw_name")
        String rawName;

        @SerializedName("target_id")
        String targetId;

        @SerializedName("user_confirmed")
        boolean userConfirmed;

        public Link(String rawName, String targetId, boolean userConfirmed) {
            this.rawName = rawName;
            this.targetId = targetId;
            this.userConfirmed = userConfirmed;
        }

        public String getRawName() {
            return rawName == null ? "" : rawName;
        }

        public String getTargetId() {
            return targetId == null ? UNCLEAR : targetId;
        }

        /** 앱의 확인 질문에 사용자가 맞다고 답했으면 true */
        public boolean isUserConfirmed() {
            return userConfirmed;
        }

        /** 규칙 대상 id로 연결됐는지 (모름·목록 밖이 아닌지) */
        public boolean isLinked() {
            return !UNCLEAR.equals(getTargetId()) && !NOT_LISTED.equals(getTargetId());
        }
    }

    public List<String> getConcerns() {
        return concerns == null ? new ArrayList<>() : concerns;
    }

    /** 고민을 고르기에 정보가 부족하면 true */
    public boolean needsMoreInfo() {
        return needsMoreInfo;
    }

    /** needsMoreInfo일 때 사용자에게 할 질문 한 개 */
    public String getFollowUpQuestion() {
        return followUpQuestion == null ? "" : followUpQuestion.trim();
    }

    /** 규칙 표 범위에 들어가지 않는 증상이 있으면 true (예: 속쓰림인데 위 항목이 없음) */
    public boolean isUnmatched() {
        return unmatched;
    }

    /** 사용자가 말한 지병 (예: 당뇨). 말하지 않았거나 없다고 했으면 빈 목록 */
    public List<String> getConditions() {
        return conditions == null ? new ArrayList<>() : conditions;
    }

    /** 사용자가 말한 복용 중인 약 (예: 혈압약). 말하지 않았거나 없다고 했으면 빈 목록 */
    public List<String> getMedications() {
        return medications == null ? new ArrayList<>() : medications;
    }

    /** 해당하는 병원 안내 표의 id (README 8-7). 안내 문구는 표의 고정 문구를 쓴다 */
    public List<String> getWarnings() {
        return warnings == null ? new ArrayList<>() : warnings;
    }

    /** 해당하는 응급·진료 분류 id (근거 규칙의 symptom 대상) */
    public List<String> getTriage() {
        return triage == null ? new ArrayList<>() : triage;
    }

    /** 사용자가 말한 약·지병 등의 연결 목록 */
    public List<Link> getLinks() {
        return links == null ? new ArrayList<>() : links;
    }

    /** 사용자 말을 정리한 한 줄. 추천 기록 저장(D3)에 쓴다 */
    public String getSymptomSummary() {
        return symptomSummary == null ? "" : symptomSummary.trim();
    }
}
