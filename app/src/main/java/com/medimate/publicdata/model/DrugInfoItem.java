package com.medimate.publicdata.model;

/**
 * e약은요 body.items[] 한 건. 필드명은 실제 응답 JSON과 같다.
 * *Qesitm 값에는 HTML 태그·줄바꿈이 섞여 있으므로 화면·TTS에 쓰기 전에 HtmlCleaner를 거친다.
 */
public class DrugInfoItem {

    private String entpName;            // 업체명
    private String itemName;            // 제품명
    private String itemSeq;             // 품목기준코드 (캐시 키)
    private String efcyQesitm;          // 효능
    private String useMethodQesitm;     // 사용법
    private String atpnWarnQesitm;      // 경고
    private String atpnQesitm;          // 주의사항
    private String intrcQesitm;         // 상호작용
    private String seQesitm;            // 부작용
    private String depositMethodQesitm; // 보관법
    private String openDe;              // 공개일자
    private String updateDe;            // 수정일자
    private String itemImage;           // 낱알 이미지 URL (없으면 null)
    private String bizrno;              // 사업자등록번호

    public String getEntpName() {
        return entpName;
    }

    public String getItemName() {
        return itemName;
    }

    public String getItemSeq() {
        return itemSeq;
    }

    public String getEfcyQesitm() {
        return efcyQesitm;
    }

    public String getUseMethodQesitm() {
        return useMethodQesitm;
    }

    public String getAtpnWarnQesitm() {
        return atpnWarnQesitm;
    }

    public String getAtpnQesitm() {
        return atpnQesitm;
    }

    public String getIntrcQesitm() {
        return intrcQesitm;
    }

    public String getSeQesitm() {
        return seQesitm;
    }

    public String getDepositMethodQesitm() {
        return depositMethodQesitm;
    }

    public String getOpenDe() {
        return openDe;
    }

    public String getUpdateDe() {
        return updateDe;
    }

    public String getItemImage() {
        return itemImage;
    }

    public String getBizrno() {
        return bizrno;
    }
}
