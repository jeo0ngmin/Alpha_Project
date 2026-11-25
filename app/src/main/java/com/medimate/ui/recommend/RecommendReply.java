package com.medimate.ui.recommend;

import com.medimate.recommend.KnowledgeBase;
import com.medimate.recommend.KnowledgeMessages;
import com.medimate.recommend.KnowledgeRule;
import com.medimate.recommend.RecommendEngine;
import com.medimate.recommend.RecommendRule;
import com.medimate.recommend.SymptomAnalysis;
import com.medimate.recommend.SymptomWarning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 챗봇 답변 문장을 만든다 (README 8-2, 8-7, 8-8). 순수 자바라 단위 테스트로 확인한다.
 * 추천은 규칙 표로만, 안내 문구는 표의 고정 문구로만 만든다.
 * 우선순위: 응급 > 진료 필요 > 알려진 위험 > 정보 부족 > 조건부 후보 (정책 P09).
 */
class RecommendReply {

    static final String DISCLAIMER = "이 정보는 의학적 조언이 아니에요. 드시기 전에 의사나 약사와 상담하세요.";
    /** 성분마다 반복되던 공전 주의("이상사례 발생 시 섭취를 중단하고 전문가와 상담할 것")를 끝에 한 번만 쓴다 */
    static final String MSG_ADVERSE = "드시다가 몸에 이상이 생기면 바로 멈추고 의사나 약사와 상담하세요.";
    static final String MSG_UNMATCHED = "말씀하신 증상 중 일부는 아직 추천할 수 있는 항목이 없어요. "
            + "증상이 계속되거나 심하면 의사나 약사와 상담하세요.";
    static final String MSG_HISTORY_CAUTION = "증상이 지병 때문일 수도 있고 약과 맞지 않을 수도 있으니, "
            + "드시기 전에 꼭 의사나 약사와 상담하세요.";

    private final RecommendEngine engine;
    private final List<SymptomWarning> warnings;
    private final KnowledgeBase knowledge;

    RecommendReply(RecommendEngine engine, List<SymptomWarning> warnings, KnowledgeBase knowledge) {
        this.engine = engine;
        this.warnings = warnings;
        this.knowledge = knowledge;
    }

    /**
     * 응급 안내 표(symptom_warnings.json의 emergency 줄)에 해당하면 그 고정 문구들. 없으면 빈 문자열.
     * 모든 빌드에서 쓰며, 있으면 문진·추천 없이 바로 안내한다 (README 8-7).
     */
    String emergencyWarningText(SymptomAnalysis analysis) {
        List<String> messages = new ArrayList<>();
        for (SymptomWarning warning : warnings) {
            if (warning.isEmergency() && analysis.getWarnings().contains(warning.getId())) {
                messages.add(warning.getMessage());
            }
        }
        return String.join("\n", messages);
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
        return KnowledgeMessages.emergency();
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
            sb.append(KnowledgeMessages.medicalEvaluation(medical))
              .append("\n\n").append(DISCLAIMER);
        } else {
            sb.append(recommendationText(analysis));
        }
        for (SymptomWarning warning : warnings) {
            if (!warning.isEmergency() && analysis.getWarnings().contains(warning.getId())) {
                sb.append("\n\n").append(warning.getMessage());
            }
        }
        return sb.toString();
    }

    /**
     * 증상별로 묶어 성분마다 "성분명 / 효능 / 주의" 세 줄만 보여 준다 (2026-10-30 사용자 요청: 가독성).
     * 규칙 표 성분은 고민 이름으로, AI 성분은 AI가 적은 증상으로 묶는다. 출처 줄은 넣지 않는다.
     * 확인된 약·질환에 걸리는 근거 규칙(디버그 빌드)은 해당 성분 아래에 붙인다.
     */
    private String recommendationText(SymptomAnalysis analysis) {
        List<String> concerns = analysis.getConcerns();
        List<RecommendRule> rules = engine.recommend(concerns);
        List<SymptomAnalysis.AiSuggestion> aiSuggestions = analysis.getAiSuggestions();
        // AI가 표 밖 증상의 성분을 골랐으면 "추천할 항목이 없다"는 안내는 하지 않는다
        boolean unmatched = analysis.isUnmatched() && aiSuggestions.isEmpty();
        String available = "지금 추천할 수 있는 고민은 " + String.join(", ", engine.concerns()) + "이에요.";
        if (rules.isEmpty() && aiSuggestions.isEmpty()) {
            if (unmatched) {
                return MSG_UNMATCHED + "\n" + available;
            }
            return "말씀하신 증상에 맞는 항목을 찾지 못했어요. " + available + " 이 중에서 말씀해 주세요.";
        }
        Set<String> confirmed = confirmedTargets(analysis);
        StringBuilder sb = new StringBuilder();
        String history = historyText(analysis.getConditions(), analysis.getMedications());
        // 문단마다 빈 줄 하나로 나눈다. 맨 앞에는 빈 줄을 두지 않는다
        if (!history.isEmpty()) {
            sb.append(history).append(' ').append(MSG_HISTORY_CAUTION).append("\n\n");
        }
        if (unmatched) {
            sb.append(MSG_UNMATCHED).append("\n\n");
        }
        Map<String, List<Item>> groups = new LinkedHashMap<>();
        for (RecommendRule rule : rules) {
            groupOf(groups, rule.getConcern()).add(new Item(rule.getIngredient(), rule.getReason(),
                    rule.getCaution(), rule.getIngredientIds()));
        }
        for (SymptomAnalysis.AiSuggestion s : aiSuggestions) {
            String symptom = s.getSymptom().isEmpty() ? "그 밖의 증상" : s.getSymptom();
            groupOf(groups, symptom).add(new Item(s.getIngredient(), s.getReason(), s.getCaution(),
                    Collections.singletonList(s.getIngredientId())));
        }
        for (Map.Entry<String, List<Item>> group : groups.entrySet()) {
            sb.append("[").append(group.getKey()).append("]\n");
            for (Item item : group.getValue()) {
                appendIngredient(sb, item, confirmed);
            }
        }
        List<String> unclear = unclearNames(analysis);
        List<String> noRule = noRuleNames(analysis);
        if (!knowledge.isEmpty() && !unclear.isEmpty()) {
            sb.append(KnowledgeMessages.unclear(unclear)).append("\n\n");
        }
        if (!knowledge.isEmpty() && !noRule.isEmpty()) {
            sb.append(KnowledgeMessages.noRule(noRule)).append("\n\n");
        }
        sb.append(MSG_ADVERSE).append("\n").append(DISCLAIMER);
        return sb.toString();
    }

    /** 답변에 보여 줄 성분 하나 */
    private static final class Item {
        final String ingredient;
        final String reason;
        final String caution;
        final List<String> ingredientIds;

        Item(String ingredient, String reason, String caution, List<String> ingredientIds) {
            this.ingredient = ingredient;
            this.reason = reason;
            this.caution = caution;
            this.ingredientIds = ingredientIds;
        }
    }

    private static List<Item> groupOf(Map<String, List<Item>> groups, String name) {
        List<Item> items = groups.get(name);
        if (items == null) {
            items = new ArrayList<>();
            groups.put(name, items);
        }
        return items;
    }

    /**
     * "• 성분명 / 효능: … / 주의: …" + 근거 규칙 확인 사항, 끝에 빈 줄 하나 (성분끼리 구분, 2026-10-30 사용자 요청).
     */
    private void appendIngredient(StringBuilder sb, Item item, Set<String> confirmed) {
        sb.append("• ").append(item.ingredient).append("\n");
        sb.append("  효능: ").append(item.reason).append("\n");
        if (!item.caution.isEmpty()) {
            sb.append("  주의: ").append(item.caution).append("\n");
        }
        for (KnowledgeRule k : knowledge.matching(item.ingredientIds, confirmed)) {
            sb.append("  ").append(KnowledgeMessages.ruleNote(item.ingredient, k, confirmed, knowledge)).append("\n");
        }
        sb.append("\n");
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
        for (SymptomAnalysis.AiSuggestion s : analysis.getAiSuggestions()) {
            ids.add(s.getIngredientId());
        }
        return ids;
    }

    /**
     * 답변에 실린 성분 이름 (규칙 표 + AI). 제품 링크를 만들 때 쓴다 (README 8-9).
     * 진료 우선이면 추천을 하지 않으므로 빈 목록.
     */
    List<String> recommendedIngredients(SymptomAnalysis analysis) {
        List<String> names = new ArrayList<>();
        if (!medicalRules(analysis).isEmpty()) {
            return names;
        }
        for (RecommendRule rule : engine.recommend(analysis.getConcerns())) {
            names.add(rule.getIngredient());
        }
        for (SymptomAnalysis.AiSuggestion s : analysis.getAiSuggestions()) {
            names.add(s.getIngredient());
        }
        return names;
    }

    /** 추천할 성분이 있는지 (규칙 표 또는 AI). 있으면 답변 전에 지병·약을 묻는다 */
    boolean hasCandidates(SymptomAnalysis analysis) {
        return !engine.recommend(analysis.getConcerns()).isEmpty() || !analysis.getAiSuggestions().isEmpty();
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
