package com.medimate.recommend;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 근거 규칙으로 만드는 안내 문장 (README 8-8). 문장 틀은 고정이고, 내용은 규칙의 근거 요약·적용 한계만 쓴다.
 * AI가 만든 문장을 넣지 않는다. 출처는 화면·낭독에 보여 주지 않는다 (2026-10-30 결정, 데이터에는 남긴다).
 */
public final class KnowledgeMessages {

    private KnowledgeMessages() {
    }

    /** 응급: 문진·추천 없이 바로 119 안내 (시작 자료 G14, G29) */
    public static String emergency() {
        return "영양제보다 즉시 응급 도움이 필요한 증상이에요. "
                + "지금 바로 119에 전화하세요. 직접 운전하지 마시고, 주변 사람에게 도움을 요청하세요.";
    }

    /** 진료 우선: 영양제 추천 대신 진료 안내 (시작 자료 G13) */
    public static String medicalEvaluation(List<KnowledgeRule> rules) {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeRule rule : rules) {
            sb.append(rule.getSummary()).append(' ').append(rule.getLimits()).append('\n');
        }
        sb.append("영양제로 해결하려고 미루지 말고 먼저 진료를 받으세요.");
        return sb.toString();
    }

    /** 추천 성분 하나에 걸린 상호작용·질환 규칙 안내 */
    public static String ruleNote(String ingredient, KnowledgeRule rule, Collection<String> confirmedTargets,
                                  KnowledgeBase kb) {
        List<String> names = new ArrayList<>();
        for (String id : rule.getTargetIds()) {
            if (confirmedTargets.contains(id)) {
                names.add(kb.termName(id));
            }
        }
        String advice = KnowledgeRule.ACTION_TIMING_REVIEW.equals(rule.getAction())
                ? "함께 드신다면 복용 간격을 제품 설명서와 약사에게 확인하세요."
                : "드시기 전에 담당 의사나 약사와 꼭 확인하세요.";
        return "⚠ " + ingredient + " — " + String.join(", ", names) + ": " + rule.getSummary() + " "
                + rule.getLimits() + " " + advice;
    }

    /** 확인 질문: Claude가 연결한 약·질환을 사용자에게 확인받는다 (정책 P02) */
    public static String confirmQuestion(List<SymptomAnalysis.Link> links, KnowledgeBase kb) {
        StringBuilder sb = new StringBuilder("말씀하신 것을 이렇게 이해했어요.");
        for (SymptomAnalysis.Link link : links) {
            sb.append("\n• ").append(link.getRawName()).append(": ").append(kb.termName(link.getTargetId()));
        }
        sb.append("\n맞으면 \"네\"라고, 틀리면 바르게 말씀해 주세요.");
        return sb.toString();
    }

    /** 성분을 확인하지 못한 약 (정책 P02, P03) */
    public static String unclear(List<String> rawNames) {
        return "말씀하신 것 중 성분을 알 수 없는 것(" + String.join(", ", rawNames) + ")이 있어서, "
                + "위 성분과 함께 드셔도 되는지 확인하지 못했어요. 약 봉투나 처방전을 약사에게 보여 주고 물어보세요.";
    }

    /** 규칙이 없는 약·질환. 규칙이 없다는 것은 안전하다는 뜻이 아니다 (정책 P03) */
    public static String noRule(List<String> rawNames) {
        return "말씀하신 것(" + String.join(", ", rawNames) + ")과 위 성분을 함께 드셔도 되는지는 확인할 수 없었어요. "
                + "문제가 없다는 뜻은 아니니, 드시기 전에 의사나 약사에게 꼭 물어보세요.";
    }
}
