package com.medimate.drug.model;

/**
 * 약 상세 화면의 버튼(카드) 하나. 서버가 순서대로 준다.
 * key: DESCRIPTION(약 설명), CAUTION(주의사항), USE_METHOD(복용방법), STORAGE(보관방법)는 항상 오고,
 * INTERACTION(함께 먹으면 안 되는 것), SIDE_EFFECT(부작용)는 내용이 있을 때만 온다.
 */
public class DrugSection {
    public static final String DESCRIPTION = "DESCRIPTION";
    public static final String CAUTION = "CAUTION";
    public static final String USE_METHOD = "USE_METHOD";
    public static final String STORAGE = "STORAGE";
    public static final String INTERACTION = "INTERACTION";
    public static final String SIDE_EFFECT = "SIDE_EFFECT";

    public String key;
    /** 버튼 제목: "약 설명", "주의사항" … */
    public String title;
    /** 본문. 자료가 없으면 null이고, 그때는 ttsText에 안내 문장이 들어 있다. */
    public String text;
    /** 읽어 줄 문장. "주의사항입니다. …" */
    public String ttsText;

    public boolean hasText() {
        return text != null && !text.trim().isEmpty();
    }
}
