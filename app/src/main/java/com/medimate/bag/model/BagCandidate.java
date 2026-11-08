package com.medimate.bag.model;

/** 봉투의 약 이름과 비슷한 식약처 품목 후보. 사용자가 고르면 BagItemPatch.chooseItem(itemSeq)로 보낸다. */
public class BagCandidate {
    public String itemSeq;
    public String itemName;
    public String entpName;
    /** 0~1 이름 유사도 */
    public double similarity;
}
