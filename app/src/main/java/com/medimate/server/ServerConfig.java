package com.medimate.server;

import com.medimate.BuildConfig;

/**
 * Claude 호출을 대신 보내 줄 backend 주소 (backend/README.md). secrets.properties의 SERVER_BASE_URL에서 읽는다.
 * 에뮬레이터에서 PC의 backend를 쓰면 http://10.0.2.2:8080
 * 비어 있으면 알약·약봉투 API와 같은 서버(API_BASE_URL, gradle.properties의 medimate.apiBaseUrl)를 쓴다.
 * 그래서 secrets.properties가 없어도 챗봇이 같은 PC 서버를 거쳐 Claude를 부른다.
 */
public final class ServerConfig {

    private ServerConfig() {
    }

    public static boolean isConfigured() {
        return !baseUrl().isEmpty();
    }

    /** 끝의 "/"를 뺀 주소. 예: http://10.0.2.2:8080 */
    public static String baseUrl() {
        String url = BuildConfig.SERVER_BASE_URL.trim();
        if (url.isEmpty()) {
            url = BuildConfig.API_BASE_URL.trim();
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
