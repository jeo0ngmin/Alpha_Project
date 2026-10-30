package com.medimate.ui.recommend;

import com.medimate.recommend.KnowledgeBase;
import com.medimate.recommend.KnowledgeMessages;
import com.medimate.recommend.KnowledgeRule;
import com.medimate.recommend.RecommendEngine;
import com.medimate.recommend.RecommendRule;
import com.medimate.recommend.SymptomAnalysis;
import com.medimate.recommend.SymptomWarning;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 챗봇 답변 문장을 만든다 (README 8-2, 8-7, 8-8). 순수 자바라 단위 테스트로 확인한다.
 * 추천은 규칙 표로만, 안내 문구는 표의 고정 문구로만 만든다.
 * 우선순위: 응급 > 진료 필요 > 알려진 위험 > 정보 부족 > 조건부 후보 (정책 P09).
 */
class RecommendReply {

    static final String DISCLAIMER = "이 정보는 의학적 조언이 아닙니다. 복용 전에 의사나 약사와 상담하세요.";
    static final String MSG_UNMATCHED = "말씀하신 증상 중 일부는 아직 추천할 수 있는 항목이 없어요. "
            + "증상이 계속되거나 심하면 의사나 약사와 상담하세요.";
    static final String MSG_HISTORY_CAUTION = "지금 증상이 지병 때문일 수도 있고, 영양제가 드시는 약과 맞지 않을 수도 있어요. "
            + "영양제를 드시기 전에 꼭 진료받는 의사나 약사에게 지금 증상을 말씀하고 상담하세요.";

    private final RecommendEngine engine;
    private final List<SymptomWarning> warnings;
    private final KnowledgeBase knowledge;

    RecommendReply(RecommendEngine engine, List<SymptomWarning> warnings, KnowledgeBase knowledge) {
        this.engine = engine;
        this.warnings = warnings;
        this.knowledge = knowledge;
    }

    /** 응급 분류에 걸린 규칙. 있으면 문진·추천 없이 바로 응급 안내 */
    List<KnowledgeRule> emergencyRules(SymptomAnalysis analysis) {
        return triageWith(analysis, KnowledgeRule.ACTION_EMERGENCY);
    }

    /** 진료 우선 분류에 걸린 규칙. 있으면 영양제 추천 대신 진료 안내 */
    List<KnowledgeRule> medicalRules(SymptomAnalysis analysis) {
        return triageWith(analysis, KnowledgeRule.ACTION_MEDICAL_EVALUATION);
    }

    String emergencyText(List<KnowledgeRule> rules) {
        return KnowledgeMessages.emergency(rules, knowledge) + "\n" + KnowledgeMessages.UNREVIEWED;
    }

    /**
     * 사용자에게 확인받을 연결: 추천 후보 성분에 규칙이 걸릴 수 있는 대상으로 연결됐는데 아직 확인받지 않은 것.
     * 규칙과 상관없는 연결은 묻지 않는다.
     */
    List<SymptomAnalysis.Link> linksToConfirm(SymptomAnalysis analysis) {
        Set<String> relevant = knowledge.relevantTargets(candidateIngredientIds(analysis));
        List<SymptomAnalysis.Link> result = new ArrayList<>();
        for (SymptomAnalysis.Link link : analysis.getLinks()) {
            if (link.isLinked() && !link.isUserConfirmed() && relevant.contains(link.getTargetId())) {
                result.add(link);
            }
        }
        return result;
    }

    String confirmQuestion(List<SymptomAnalysis.Link> links) {
        return KnowledgeMessages.confirmQuestion(links, knowledge);
    }

    /** 최종 답변. 진료 우선이면 추천 대신 진료 안내, 아니면 규칙 표 추천 + 근거 규칙 확인 사항. 병원 안내는 맨 끝 */
    String build(SymptomAnalysis analysis) {
        List<KnowledgeRule> medical = medicalRules(analysis);
        StringBuilder sb = new StringBuilder();
        if (!medical.isEmpty()) {
            sb.append(KnowledgeMessages.medicalEvaluation(medical, knowledge))
              .append("\n").append(KnowledgeMessages.UNREVIEWED)
              .append("\n\n").append(DISCLAIMER);
        } else {
            sb.append(recommendationText(analysis));
        }
        for (SymptomWarning warning : warnings) {
            if (analysis.getWarnings().contains(warning.getId())) {
                sb.append("\n\n").append(warning.getMessage());
            }
        }
        return sb.toString();
    }

    private String recommendationText(SymptomAnalysis analysis) {
        List<String> concerns = analysis.getConcerns();
        boolean unmatched = analysis.isUnmatched();
        List<RecommendRule> rules = engine.recommend(concerns);
        String available = "지금 추천할 수 있는 고민은 " + String.join(", ", engine.concerns()) + "이에요.";
        if (rules.isEmpty()) {
            if (unmatched) {
                return MSG_UNMATCHED + "\n" + available;
            }
            return "말씀하신 증상에 맞는 항목을 찾지 못했어요. " + available + " 이 중에서 말씀해 주세요.";
        }
        Set<String> confirmed = confirmedTargets(analysis);
        StringBuilder sb = new StringBuilder();
        String history = historyText(analysis.getConditions(), analysis.getMedications());
        if (!history.isEmpty()) {
            sb.append(history).append(' ').append(MSG_HISTORY_CAUTION).append("\n\n");
        }
        if (unmatched) {
            sb.append(MSG_UNMATCHED).append("\n\n");
        }
        sb.append(String.join(", ", concerns)).append("에 도움이 될 수 있는 성분이에요.\n");
        boolean usedKnowledge = false;
        for (RecommendRule rule : rules) {
            sb.append("\n• ").append(rule.getIngredient()).append(": ").append(rule.getReason())
              .append("\n  (근거: ").append(rule.getSource()).append(")\n");
            if (!rule.getCaution().isEmpty()) {
                sb.append("  섭취 시 주의: ").append(rule.getCaution())
                  .append("\n  (주의사항 근거: ").append(rule.getCautionSource()).append(")\n");
            }
            for (KnowledgeRule k : knowledge.matching(rule.getIngredientIds(), confirmed)) {
                sb.append("  ").append(KnowledgeMessages.ruleNote(rule.getIngredient(), k, confirmed, knowledge))
                  .append("\n");
                usedKnowledge = true;
            }
        }
        List<String> unclear = unclearNames(analysis);
        List<String> noRule = noRuleNames(analysis);
        if (!knowledge.isEmpty() && !unclear.isEmpty()) {
            sb.append("\n").append(KnowledgeMessages.unclear(unclear)).append("\n");
            usedKnowledge = true;
        }
        if (!knowledge.isEmpty() && !noRule.isEmpty()) {
            sb.append("\n").append(KnowledgeMessages.noRule(noRule)).append("\n");
            usedKnowledge = true;
        }
        if (usedKnowledge) {
            sb.append("\n").append(KnowledgeMessages.UNREVIEWED).append("\n");
        }
        sb.append("\n").append(DISCLAIMER);
        return sb.toString();
    }

    /** 사용자가 맞다고 확인한 연결 대상 id */
    private static Set<String> confirmedTargets(SymptomAnalysis analysis) {
        Set<String> ids = new LinkedHashSet<>();
        for (SymptomAnalysis.Link link : analysis.getLinks()) {
            if (link.isLinked() && link.isUserConfirmed()) {
                ids.add(link.getTargetId());
            }
        }
        return ids;
    }

    /** 성분을 모르는 것, 또는 연결했지만 사용자가 확인해 주지 않은 것 (정책 P02) */
    private List<String> unclearNames(SymptomAnalysis analysis) {
        Set<String> relevant = knowledge.relevantTargets(candidateIngredientIds(analysis));
        List<String> names = new ArrayList<>();
        for (SymptomAnalysis.Link link : analysis.getLinks()) {
            boolean unconfirmed = link.isLinked() && !link.isUserConfirmed() && relevant.contains(link.getTargetId());
            if (SymptomAnalysis.Link.UNCLEAR.equals(link.getTargetId()) || unconfirmed) {
                names.add(link.getRawName());
            }
        }
        return names;
    }

    /** 규칙이 하나도 걸리지 않는 약·질환. "모름"이지 "안전"이 아니다 (정책 P03) */
    private List<String> noRuleNames(SymptomAnalysis analysis) {
        Set<String> relevant = knowledge.relevantTargets(candidateIngredientIds(analysis));
        List<String> names = new ArrayList<>();
        for (SymptomAnalysis.Link link : analysis.getLinks()) {
            boolean notListed = SymptomAnalysis.Link.NOT_LISTED.equals(link.getTargetId());
            boolean irrelevant = link.isLinked() && !relevant.contains(link.getTargetId());
            if (notListed || irrelevant) {
                names.add(link.getRawName());
            }
        }
        return names;
    }

    private Set<String> candidateIngredientIds(SymptomAnalysis analysis) {
        Set<String> ids = new LinkedHashSet<>();
        for (RecommendRule rule : engine.recommend(analysis.getConcerns())) {
            ids.addAll(rule.getIngredientIds());
        }
        return ids;
    }

    private List<KnowledgeRule> triageWith(SymptomAnalysis analysis, String action) {
        List<KnowledgeRule> result = new ArrayList<>();
        for (KnowledgeRule rule : knowledge.triageFor(analysis.getTriage())) {
            if (action.equals(rule.getAction())) {
                result.add(rule);
            }
        }
        return result;
    }

    /**
     * 사용자가 말한 지병·약을 되짚는다. 둘 다 없으면 빈 문자열.
     * 예: "말씀하신 지병(당뇨)과 드시는 약(혈압약)이 있으시네요." 조사가 '병', '약'에 붙어 낭독해도 자연스럽다.
     */
    static String historyText(List<String> conditions, List<String> medications) {
        String disease = conditions.isEmpty() ? "" : "지병(" + String.join(", ", conditions) + ")";
        String drug = medications.isEmpty() ? "" : "드시는 약(" + String.join(", ", medications) + ")";
        if (disease.isEmpty() && drug.isEmpty()) {
            return "";
        }
        if (disease.isEmpty()) {
            return "말씀하신 " + drug + "이 있으시네요.";
        }
        if (drug.isEmpty()) {
            return "말씀하신 " + disease + "이 있으시네요.";
        }
        return "말씀하신 " + disease + "과 " + drug + "이 있으시네요.";
    }
}
