package com.medimate.server;

import org.springframework.http.HttpStatus;

/** 앱에 돌려줄 오류. message는 개발용 짧은 설명이고, 사용자 안내 문장은 앱이 정한다. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
