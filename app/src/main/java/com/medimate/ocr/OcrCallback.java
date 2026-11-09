package com.medimate.ocr;

/** OCR 결과 전달 (README 4-0). 메인 스레드에서 호출된다. */
public interface OcrCallback {

    /** 인식된 글자. 글자를 찾지 못하면 빈 문자열. */
    void onSuccess(String text);

    /** 사용자에게 그대로 읽어 줄 수 있는 실패 안내 문장. */
    void onError(String message);
}
