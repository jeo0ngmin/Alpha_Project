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

    @SerializedName("symptom_summary")
    String symptomSummary;

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

    /** 사용자 말을 정리한 한 줄. 추천 기록 저장(D3)에 쓴다 */
    public String getSymptomSummary() {
        return symptomSummary == null ? "" : symptomSummary.trim();
    }
}
