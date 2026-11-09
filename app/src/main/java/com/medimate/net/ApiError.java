package com.medimate.net;

import java.util.List;

/** 명세의 ErrorBody. ttsMessage는 화면을 보지 않는 사용자에게 그대로 읽어 준다. */
public class ApiError {
    public String code;
    public String message;
    public String ttsMessage;
    public List<FieldReason> details;
    public boolean retryable;

    public static class FieldReason {
        public String field;
        public String reason;
    }

    public static ApiError of(String code, String message, String ttsMessage, boolean retryable) {
        ApiError e = new ApiError();
        e.code = code;
        e.message = message;
        e.ttsMessage = ttsMessage;
        e.retryable = retryable;
        return e;
    }
}
