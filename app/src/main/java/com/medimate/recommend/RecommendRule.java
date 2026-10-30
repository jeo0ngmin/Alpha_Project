package com.medimate.recommend;

import java.util.ArrayList;
import java.util.List;

/** 규칙 표(assets/supplement_rules.json) 한 줄 (README 8-3). 모든 항목에 근거 출처가 있어야 한다. */
public class RecommendRule {

    private String concern;     // 건강 고민 이름
    private String ingredient;  // 추천 성분
    private String reason;      // 화면·낭독에 쓸 한 문장 설명
    private String source;      // 근거 출처 (기관, 문서명)
    private String caution;       // 섭취 시 주의사항 (과다 섭취, 복용 방법 등). 없으면 비워 둔다
    private String cautionSource; // 주의사항의 근거 출처. 비어 있으면 주의사항을 보여 주지 않는다
    private List<String> ingredientIds; // 근거 규칙(knowledge_rules)의 성분 id. 제형을 모르면 가능한 제형도 넣는다

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

    /** 근거 출처가 있는 주의사항만 돌려준다 (README 8-1). 없으면 빈 문자열 */
    public String getCaution() {
        return caution == null || getCautionSource().isEmpty() ? "" : caution;
    }

    public String getCautionSource() {
        return cautionSource == null ? "" : cautionSource;
    }

    public List<String> getIngredientIds() {
        return ingredientIds == null ? new ArrayList<>() : ingredientIds;
    }
}
