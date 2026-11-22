package com.medimate.server;

import com.medimate.BuildConfig;

/**
 * 우리 서버 주소 (server/README.md). secrets.properties의 SERVER_BASE_URL에서 읽는다.
 * 비어 있으면 서버 없이 지금처럼 동작한다: 카카오는 Firebase 연결 없이 로그인만, Claude는 앱의 키로 직접 호출.
 */
public final class ServerConfig {

    private ServerConfig() {
    }

    public static boolean isConfigured() {
        return !baseUrl().isEmpty();
    }

    /** 끝의 "/"를 뺀 주소. 예: https://1-2-3-4.sslip.io */
    public static String baseUrl() {
        String url = BuildConfig.SERVER_BASE_URL.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
