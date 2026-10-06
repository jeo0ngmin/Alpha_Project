package com.medimate.gpt;

import com.medimate.gpt.model.GptDrugList;

/** GPT 정리 결과 전달. 메인 스레드에서 호출된다. */
public interface GptCallback {

    void onSuccess(GptDrugList result);

    /** 사용자에게 그대로 읽어 줄 수 있는 실패 안내 문장. */
    void onError(String message);
}
