package com.medimate.data;

import java.util.List;

/**
 * 추천 기록 저장소 (README 9-2, 9-4, 10-2 D3). 화면·ViewModel은 Firestore 대신 이 인터페이스만 참조한다.
 * 대화 본문·지병·복용 약은 저장하지 않는다. 고민과 추천 성분만 둔다.
 */
public interface RecommendationRepository {

    /**
     * 추천 결과 한 건을 저장한다. 로그인하지 않았으면(카카오 포함) 조용히 건너뛴다.
     * 실패해도 상담 흐름은 막지 않으므로 결과를 돌려주지 않는다.
     *
     * @param concerns    규칙 표의 고민 이름 + 규칙 표에 없어 AI가 추천한 증상
     * @param ingredients 추천한 성분 이름
     */
    void save(List<String> concerns, List<String> ingredients);
}
