package com.medimate.server.common;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 어떤 오류든 앱이 읽을 수 있는 공통 형식(ErrorResponse + ttsMessage)으로 내보낸다. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String CHECK_INPUT = "입력한 내용을 다시 확인해 주세요.";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Api.ErrorResponse> handle(ApiException e) {
        return body(e.status, e.code, e.getMessage(), e.ttsMessage, e.details, e.retryable, e.retryAfterSeconds);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Api.ErrorResponse> handle(MethodArgumentNotValidException e) {
        List<ApiException.FieldReason> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiException.FieldReason(f.getField(), f.getDefaultMessage())).toList();
        return badRequest("요청 값을 확인해 주세요.", details);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Api.ErrorResponse> handle(HandlerMethodValidationException e) {
        return badRequest("요청 값을 확인해 주세요.", null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Api.ErrorResponse> handle(HttpMessageNotReadableException e) {
        // 알 수 없는 enum 값이나 깨진 JSON. 내부 클래스 이름이 새지 않게 원문 메시지는 내보내지 않는다
        return badRequest("요청 본문을 해석할 수 없습니다.", null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Api.ErrorResponse> handle(MethodArgumentTypeMismatchException e) {
        return badRequest("요청 값을 확인해 주세요.",
                List.of(new ApiException.FieldReason(e.getName(), "값의 형식이 올바르지 않습니다.")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<Api.ErrorResponse> handle(MissingServletRequestParameterException e) {
        return badRequest("요청 값을 확인해 주세요.",
                List.of(new ApiException.FieldReason(e.getParameterName(), "필수 값입니다.")));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<Api.ErrorResponse> handle(MissingServletRequestPartException e) {
        return badRequest("요청 값을 확인해 주세요.",
                List.of(new ApiException.FieldReason(e.getRequestPartName(), "필수 값입니다.")));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Api.ErrorResponse> handle(MissingRequestHeaderException e) {
        return badRequest("요청 값을 확인해 주세요.",
                List.of(new ApiException.FieldReason(e.getHeaderName(), "필수 헤더입니다.")));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Api.ErrorResponse> handle(MaxUploadSizeExceededException e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "MM-FIL-4130", "이미지는 10MB까지 올릴 수 있습니다.",
                "사진 용량이 너무 큽니다. 다시 찍어 주세요.", null, false, null);
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<Api.ErrorResponse> handle(MultipartException e) {
        return badRequest("사진 파일을 읽을 수 없습니다.", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Api.ErrorResponse> handle(HttpMediaTypeNotSupportedException e) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MM-COM-4150", "지원하지 않는 요청 형식입니다.",
                "요청을 처리할 수 없습니다.", null, false, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Api.ErrorResponse> handle(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "MM-COM-4050", "지원하지 않는 요청입니다.",
                "요청을 처리할 수 없습니다.", null, false, null);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<Api.ErrorResponse> handleNotFound(Exception e) {
        return body(HttpStatus.NOT_FOUND, "MM-COM-4040", "없는 주소입니다.", "요청을 처리할 수 없습니다.", null, false, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Api.ErrorResponse> handle(Exception e) {
        log.error("처리되지 않은 오류", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "MM-COM-5000", "서버 오류가 발생했습니다.",
                "일시적인 문제가 생겼습니다. 잠시 후 다시 시도해 주세요.", null, true, null);
    }

    private static ResponseEntity<Api.ErrorResponse> badRequest(String message, List<ApiException.FieldReason> details) {
        return body(HttpStatus.BAD_REQUEST, "MM-COM-4000", message, CHECK_INPUT, details, false, null);
    }

    private static ResponseEntity<Api.ErrorResponse> body(HttpStatus status, String code, String message, String tts,
                                                          List<ApiException.FieldReason> details, boolean retryable,
                                                          Integer retryAfter) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (retryAfter != null) {
            b.header(HttpHeaders.RETRY_AFTER, retryAfter.toString());
        }
        return b.body(Api.error(new Api.ErrorBody(code, message, tts, details, retryable)));
    }
}
