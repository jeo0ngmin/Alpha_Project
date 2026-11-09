package com.medimate.net;

/** 백엔드 공통 래퍼: { success, data | error, timestamp, requestId }. */
public class ApiResponse<T> {
    public boolean success;
    public T data;
    public ApiError error;
    public String timestamp;
    public String requestId;
}
