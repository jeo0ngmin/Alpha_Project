package com.medimate.recommend;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 챗봇 대화에서 사용자 증상을 파악한다 (README 8-2, 8-6).
 * 화면·ViewModel은 이 인터페이스만 참조한다. 추천 성분은 정하지 않는다 (RecommendEngine 몫).
 */
public interface SymptomAnalyzer {

    /** 대화 한 마디. 사용자 말이거나 봇의 추가 질문 */
    final class Turn {
        public final boolean fromUser;
        public final String text;

        public Turn(boolean fromUser, String text) {
            this.fromUser = fromUser;
            this.text = text;
        }
    }

    /** 분류기가 고를 수 있는 이름들. 모두 이름 → 설명이며, 결과는 이 이름 안에서만 나온다 */
    final class Vocabulary {
        /** 규칙 표의 고민 이름 → 그 고민이 다루는 범위 */
        public final Map<String, String> concerns;
        /** 병원 진료 안내 id → 다루는 증상 (README 8-7) */
        public final Map<String, String> warnings;
        /** 응급·진료 분류 id → 상황 설명 (README 8-8, 디버그 빌드만) */
        public final Map<String, String> triage;
        /** 약·질환 연결 대상 id → 한국어 이름 (README 8-8, 디버그 빌드만) */
        public final Map<String, String> linkTargets;

        public Vocabulary(Map<String, String> concerns, Map<String, String> warnings,
                          Map<String, String> triage, Map<String, String> linkTargets) {
            this.concerns = new LinkedHashMap<>(concerns);
            this.warnings = new LinkedHashMap<>(warnings);
            this.triage = new LinkedHashMap<>(triage);
            this.linkTargets = new LinkedHashMap<>(linkTargets);
        }
    }

    interface Callback {
        /** 메인 스레드에서 불린다 */
        void onResult(SymptomAnalysis analysis);

        /** 메인 스레드에서 불린다. message는 사용자에게 읽어 줄 문장 */
        void onError(String message);
    }

    /** @param conversation 이번 상담의 대화 (사용자 말로 끝난다) */
    void analyze(List<Turn> conversation, Vocabulary vocabulary, Callback callback);
}
