package com.medimate.recommend;

import java.util.List;

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

    interface Callback {
        /** 메인 스레드에서 불린다 */
        void onResult(SymptomAnalysis analysis);

        /** 메인 스레드에서 불린다. message는 사용자에게 읽어 줄 문장 */
        void onError(String message);
    }

    /**
     * @param conversation 이번 상담의 대화 (사용자 말로 끝난다)
     * @param concerns     규칙 표의 고민 이름 목록. 결과는 이 안에서만 고른다
     */
    void analyze(List<Turn> conversation, List<String> concerns, Callback callback);
}
