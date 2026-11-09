package com.medimate.recommend;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * 시작 자료(supplement_chatbot_starter_v1)의 근거 규칙 한 줄 (README 8-8).
 * 상호작용·질환 주의·응급 분류를 담는다. 모두 전문가 검토 전이라 디버그 빌드에서만 쓴다.
 */
public class KnowledgeRule {

    public static final String KIND_TRIAGE = "triage";
    public static final String ACTION_EMERGENCY = "emergency";
    public static final String ACTION_MEDICAL_EVALUATION = "medical_evaluation";
    public static final String ACTION_TIMING_REVIEW = "timing_review";

    @SerializedName("rule_id")
    String ruleId;

    @SerializedName("kind")
    String kind;

    @SerializedName("ingredient_id")
    String ingredientId;

    @SerializedName("target_type")
    String targetType;

    @SerializedName("target_ids")
    List<String> targetIds;

    @SerializedName("evidence_summary_ko")
    String summary;

    @SerializedName("applicability_limits_ko")
    String limits;

    @SerializedName("proposed_bot_action")
    String action;

    @SerializedName("source_ids")
    List<String> sourceIds;

    public String getRuleId() {
        return ruleId == null ? "" : ruleId;
    }

    public String getKind() {
        return kind == null ? "" : kind;
    }

    public boolean isTriage() {
        return KIND_TRIAGE.equals(kind);
    }

    public String getIngredientId() {
        return ingredientId == null ? "" : ingredientId;
    }

    public String getTargetType() {
        return targetType == null ? "" : targetType;
    }

    public List<String> getTargetIds() {
        return targetIds == null ? new ArrayList<>() : targetIds;
    }

    public String getSummary() {
        return summary == null ? "" : summary;
    }

    public String getLimits() {
        return limits == null ? "" : limits;
    }

    public String getAction() {
        return action == null ? "" : action;
    }

    public List<String> getSourceIds() {
        return sourceIds == null ? new ArrayList<>() : sourceIds;
    }
}
