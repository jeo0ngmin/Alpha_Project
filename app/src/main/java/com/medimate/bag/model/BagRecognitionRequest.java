package com.medimate.bag.model;

/** POST /recognition/medication-bags 본문. 보통 fileId만 보낸다(서버가 OCR까지 한다). */
public class BagRecognitionRequest {
    public final String fileId;
    /** 앱에서 이미 OCR을 했다면 원문. 주면 서버는 OCR을 하지 않는다. */
    public final String ocrRawText;
    /** 조제일을 알면 yyyy-MM-dd */
    public final String hintDispensedDate;

    public BagRecognitionRequest(String fileId, String ocrRawText, String hintDispensedDate) {
        this.fileId = fileId;
        this.ocrRawText = ocrRawText;
        this.hintDispensedDate = hintDispensedDate;
    }
}
