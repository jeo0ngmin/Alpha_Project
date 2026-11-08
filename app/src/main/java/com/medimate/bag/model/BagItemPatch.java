package com.medimate.bag.model;

import java.util.List;

/**
 * PATCH /recognition/jobs/{jobId}/items/{itemId}: 확정 전에 약 하나를 고친다. 바꿀 값만 채운다.
 * 어떤 값이든 보내면 "사용자가 확인한 약"이 되어 needsReview가 풀린다(빈 본문도 확인으로 본다).
 */
public class BagItemPatch {
    /** 후보(BagItem.candidates) 중 고른 품목 */
    public String itemSeq;
    /** 0.25 ~ 20 */
    public Double dosageAmount;
    /** 1 ~ 6 */
    public Integer frequencyPerDay;
    public List<String> timeSlots;
    public String mealRelation;
    /** 1 ~ 365 */
    public Integer durationDays;
    /** true면 목록에서 뺀다 */
    public Boolean excluded;

    /** 값은 그대로 두고 "확인했음"만 알린다. */
    public static BagItemPatch confirmAsIs() {
        return new BagItemPatch();
    }

    public static BagItemPatch exclude() {
        BagItemPatch p = new BagItemPatch();
        p.excluded = true;
        return p;
    }

    public static BagItemPatch chooseItem(String itemSeq) {
        BagItemPatch p = new BagItemPatch();
        p.itemSeq = itemSeq;
        return p;
    }
}
