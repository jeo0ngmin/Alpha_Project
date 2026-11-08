package com.medimate.drug.model;

/** 식약처 e약은요 원문 항목. 화면은 보통 sections를 쓰고, 이건 필요할 때만 쓴다. */
public class DrugInfo {
    /** 효능 */
    public String efficacy;
    /** 사용법 */
    public String useMethod;
    /** 경고 */
    public String warning;
    /** 주의사항 */
    public String precaution;
    /** 상호작용 */
    public String interaction;
    /** 부작용 */
    public String sideEffect;
    /** 보관법 */
    public String depositMethod;
}
