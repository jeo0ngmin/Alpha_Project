package com.medimate.model;

import java.io.Serializable;

/**
 * 화면에 보여 줄 약 한 개 (README 6-3). 이름·용법은 OCR 원문(GPT 정리), 설명·주의사항은 e약은요.
 * 화면용·낭독용 분리와 낭독 문장 변환은 2차.
 */
public class Drug implements Serializable {

    public static final String NO_OFFICIAL_INFO = "공식 정보를 찾지 못했어요";

    private final String name;         // e약은요 itemName으로 교정된 이름. 못 찾으면 OCR 표기
    private final String entpName;     // 업체명 (없으면 "")
    private final String itemSeq;      // 품목기준코드 (없으면 "")
    private final String dosage;       // OCR 원문의 용법
    private final String storage;      // OCR 원문 우선, 없으면 e약은요 보관법
    private final String description;  // e약은요 효능
    private final String warning;      // e약은요 경고 + 주의사항

    public Drug(String name, String entpName, String itemSeq, String dosage, String storage,
                String description, String warning) {
        this.name = name;
        this.entpName = entpName;
        this.itemSeq = itemSeq;
        this.dosage = dosage;
        this.storage = storage;
        this.description = description;
        this.warning = warning;
    }

    public String getName() {
        return name;
    }

    public String getEntpName() {
        return entpName;
    }

    public String getItemSeq() {
        return itemSeq;
    }

    public String getDosage() {
        return dosage;
    }

    public String getStorage() {
        return storage;
    }

    public String getDescription() {
        return description;
    }

    public String getWarning() {
        return warning;
    }

    /** e약은요에서 찾았는지 */
    public boolean hasOfficialInfo() {
        return !itemSeq.isEmpty();
    }
}
