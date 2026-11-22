package com.medimate.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 메디메이트 서버 (server/README.md).
 * Claude 호출 중계 — 키를 앱(APK)에 넣지 않는다 (README 10-3 C).
 * Firebase는 로그인한 사용자인지 확인하는 데만 쓴다.
 */
@SpringBootApplication
public class MedimateServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MedimateServerApplication.class, args);
    }
}
