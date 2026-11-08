package com.medimate.bag.model;

/** 인식 실패 이유. ttsMessage를 그대로 읽어 주면 된다. */
public class JobError {
    public String code;
    public String message;
    public String ttsMessage;
    /** true면 다시 찍어서 재시도할 만하다. */
    public boolean retryable;
}
