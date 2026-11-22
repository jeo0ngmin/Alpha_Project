package com.medimate.bag.model;

import java.util.Collections;
import java.util.List;

import com.medimate.drug.model.DrugSection;

/** 약봉투에서 읽은 약 하나. */
public class BagItem {
    public String itemId;
    /** 봉투에 적힌 그대로의 이름 */
    public String rawText;
    /** 오타 등을 고친 이름 */
    public String refinedName;
    /** 식약처 품목과 연결됐는지 */
    public boolean matched;
    public String itemSeq;
    /** 식약처 품목명. 연결되지 않았으면 null이므로 화면에는 displayName()을 쓴다. */
    public String itemName;
    public String entpName;
    public String dosageForm;
    public String strength;
    public Double dosageAmount;
    /** TABLET | CAPSULE | PACK | ML | DROP | PUFF | UNIT */
    public String dosageUnit;
    public Integer frequencyPerDay;
    /** MORNING | LUNCH | DINNER | BEDTIME */
    public List<String> timeSlots;
    /** 봉투에 시점이 없어서 횟수로 추정했는지 */
    public boolean slotsInferred;
    /** BEFORE_MEAL | AFTER_MEAL | WITH_MEAL | EMPTY_STOMACH | ANYTIME */
    public String mealRelation;
    public Integer mealOffsetMinutes;
    public Integer durationDays;
    public Double totalQuantity;
    public String instructions;
    /** 봉투에 적힌 이 약의 주의사항 */
    public List<String> cautions;
    /** 목록 한 줄 요약: "1회 1정 · 하루 3회 · 아침, 점심, 저녁 · 식후 30분 · 5일분" */
    public String displayText;
    public double confidence;
    /** true면 사용자가 확인해야 한다(이름·복용량을 확실히 못 읽음). 이유는 reviewReasons. */
    public boolean needsReview;
    public List<String> reviewReasons;
    public List<BagCandidate> candidates;
    /** 사용자가 목록에서 뺀 약 */
    public boolean excluded;
    public String ttsScript;
    /** 약 상세 화면 버튼(약 설명, 주의사항, 복용방법, 보관방법 …). 별도 조회 없이 바로 쓴다. */
    public List<DrugSection> sections;
    /** FOUND | NOT_IN_MFDS | UNAVAILABLE | NOT_CONFIGURED | NOT_MATCHED */
    public String infoStatus;

    /** 화면에 보일 이름: 식약처 품목명, 없으면 봉투의 이름. */
    public String displayName() {
        if (itemName != null) {
            return itemName;
        }
        return refinedName != null ? refinedName : rawText;
    }

    public List<DrugSection> sectionsOrEmpty() {
        return sections == null ? Collections.emptyList() : sections;
    }

    public List<BagCandidate> candidatesOrEmpty() {
        return candidates == null ? Collections.emptyList() : candidates;
    }
}
