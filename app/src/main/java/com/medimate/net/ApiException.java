package com.medimate.net;

import java.io.IOException;

import okhttp3.ResponseBody;

/** 서버가 에러 래퍼로 응답했거나, 네트워크가 끊겼을 때. */
public class ApiException extends Exception {

    /** 네트워크 오류일 때의 HTTP 상태 (응답 자체가 없음). */
    public static final int NO_RESPONSE = -1;
    public static final String CODE_NETWORK = "APP-NET-0000";
    public static final String CODE_UNEXPECTED = "APP-UNX-0000";

    private final int httpStatus;
    private final ApiError error;

    public ApiException(int httpStatus, ApiError error) {
        super(error.code + " " + error.message);
        this.httpStatus = httpStatus;
        this.error = error;
    }

    public static ApiException network(IOException cause) {
        ApiException e = new ApiException(NO_RESPONSE, ApiError.of(CODE_NETWORK,
                "서버에 연결할 수 없습니다. 인터넷 연결을 확인해 주세요.",
                "서버에 연결할 수 없습니다. 인터넷 연결을 확인하고 다시 시도해 주세요.", true));
        e.initCause(cause);
        return e;
    }

    static ApiException fromErrorBody(int httpStatus, ResponseBody body) {
        try {
            if (body != null) {
                ApiResponse<?> parsed = ApiClient.GSON.fromJson(body.charStream(), ApiResponse.class);
                if (parsed != null && parsed.error != null) {
                    return new ApiException(httpStatus, parsed.error);
                }
            }
        } catch (RuntimeException ignored) {
            // 에러 래퍼가 아닌 응답 (프록시 오류 페이지 등)
        }
        return new ApiException(httpStatus, ApiError.of(CODE_UNEXPECTED,
                "알 수 없는 오류가 발생했습니다. (HTTP " + httpStatus + ")",
                "알 수 없는 오류가 생겼습니다. 잠시 후 다시 시도해 주세요.", httpStatus >= 500));
    }

    public int httpStatus() {
        return httpStatus;
    }

    public ApiError error() {
        return error;
    }

    public boolean isRetryable() {
        return error.retryable;
    }

    /** 사진 품질 문제(흐림·어두움·과노출)라서 다시 찍어야 하는 경우. */
    public boolean isRetakeNeeded() {
        return httpStatus == 422 || (error.code != null && error.code.startsWith("MM-FIL-415"));
    }
}
