package com.medimate.server.common;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;

import org.slf4j.MDC;

/** 공통 응답 래퍼와 페이지 정보. 앱은 success를 보고 data 또는 error를 읽는다. */
public final class Api {

    private Api() {
    }

    public record Response<T>(boolean success, T data, OffsetDateTime timestamp, String requestId) {
    }

    public record ErrorResponse(boolean success, ErrorBody error, OffsetDateTime timestamp, String requestId) {
    }

    public record ErrorBody(String code, String message, String ttsMessage, List<ApiException.FieldReason> details,
                            boolean retryable) {
    }

    public record PageInfo(int size, boolean hasNext, String nextCursor, long totalCount) {
    }

    public record Page<T>(List<T> items, PageInfo pageInfo) {
    }

    public static <T> Response<T> ok(T data) {
        return new Response<>(true, data, Times.nowOffset(), MDC.get(RequestIdFilter.MDC_KEY));
    }

    public static ErrorResponse error(ErrorBody body) {
        return new ErrorResponse(false, body, Times.nowOffset(), MDC.get(RequestIdFilter.MDC_KEY));
    }

    /** 커서는 마지막 행 ID를 감춘 불투명 문자열이다. */
    public static String encodeCursor(long id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Long.toString(id).getBytes(StandardCharsets.UTF_8));
    }

    public static String decodeCursorText(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("cursor", "커서 값이 올바르지 않습니다.");
        }
    }

    public static Long decodeCursor(String cursor) {
        String text = decodeCursorText(cursor);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("cursor", "커서 값이 올바르지 않습니다.");
        }
    }

    public static int pageSize(Integer size) {
        if (size == null) {
            return 20;
        }
        if (size < 1 || size > 100) {
            throw ApiException.badRequest("size", "size는 1에서 100 사이여야 합니다.");
        }
        return size;
    }
}
