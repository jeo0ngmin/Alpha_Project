package com.medimate.recommend;

/** 규칙 표(assets/supplement_rules.json) 한 줄 (README 8-3). 모든 항목에 근거 출처가 있어야 한다. */
public class RecommendRule {

    private String concern;     // 건강 고민 이름
    private String ingredient;  // 추천 성분
    private String reason;      // 화면·낭독에 쓸 한 문장 설명
    private String source;      // 근거 출처 (기관, 문서명)

    public String getConcern() {
        return concern == null ? "" : concern;
    }

    public String getIngredient() {
        return ingredient == null ? "" : ingredient;
    }

    public String getReason() {
        return reason == null ? "" : reason;
    }

    public String getSource() {
        return source == null ? "" : source;
    }
}
