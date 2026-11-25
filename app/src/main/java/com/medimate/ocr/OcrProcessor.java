package com.medimate.ocr;

import android.graphics.Bitmap;

/**
 * OCR 구현을 가리는 인터페이스 (README 4-0).
 * 화면과 ViewModel은 이 인터페이스만 참조한다. 지금은 ML Kit, 나중에 CLOVA로 교체할 수 있다.
 */
public interface OcrProcessor {

    void process(Bitmap bitmap, OcrCallback callback);

    /** 화면이 닫힐 때 호출해 인식기를 해제한다. */
    void close();
}
