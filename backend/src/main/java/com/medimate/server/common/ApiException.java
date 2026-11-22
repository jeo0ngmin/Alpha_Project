package com.medimate.server.common;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 모든 업무 오류. code는 MM-{도메인}-{HTTP}{일련번호} 체계이고,
 * ttsMessage는 화면을 보지 않는 사용자에게 그대로 읽어 줄 한 문장이다.
 */
public class ApiException extends RuntimeException {

    public final HttpStatus status;
    public final String code;
    public final String ttsMessage;
    public final boolean retryable;
    public final List<FieldReason> details;
    public final Integer retryAfterSeconds;

    public record FieldReason(String field, String reason) {
    }

    public ApiException(HttpStatus status, String code, String message, String ttsMessage, boolean retryable,
                        List<FieldReason> details, Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.ttsMessage = ttsMessage;
        this.retryable = retryable;
        this.details = details;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ApiException of(HttpStatus status, String code, String message, String ttsMessage) {
        return new ApiException(status, code, message, ttsMessage, false, null, null);
    }

    public static ApiException retryable(HttpStatus status, String code, String message, String ttsMessage) {
        return new ApiException(status, code, message, ttsMessage, true, null, null);
    }

    public static ApiException badRequest(String field, String reason) {
        return new ApiException(HttpStatus.BAD_REQUEST, "MM-COM-4000", "요청 값을 확인해 주세요.",
                "입력한 내용을 다시 확인해 주세요.", false, List.of(new FieldReason(field, reason)), null);
    }

    public static ApiException notFound(String what) {
        return of(HttpStatus.NOT_FOUND, "MM-COM-4040", what + "을(를) 찾을 수 없습니다.", "찾으시는 정보가 없습니다.");
    }

    public static ApiException forbidden(String message) {
        return of(HttpStatus.FORBIDDEN, "MM-COM-4030", message, "이 정보를 볼 권한이 없습니다.");
    }

    public static ApiException conflict(String code, String message, String ttsMessage) {
        return of(HttpStatus.CONFLICT, code, message, ttsMessage);
    }

    public static ApiException gone(String code, String message, String ttsMessage) {
        return of(HttpStatus.GONE, code, message, ttsMessage);
    }

    public static ApiException tooMany(String code, String message, String ttsMessage, int retryAfterSeconds) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, message, ttsMessage, true, null, retryAfterSeconds);
    }

    public static ApiException unauthorized(String code, String message, String ttsMessage) {
        return of(HttpStatus.UNAUTHORIZED, code, message, ttsMessage);
    }
}
