package com.medimate.server.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml의 medimate.* 설정. 비밀 값은 환경변수나 키 파일로만 들어온다. */
@ConfigurationProperties("medimate")
public record MedimateProperties(Storage storage, Limits limits, Pill pill, Mfds mfds, Auth auth) {

    public record Storage(String dir, int retentionDays, int signedUrlMinutes) {
    }

    public record Limits(int dailyPill, int uploadsPerHour, int dailyRecognition) {
    }

    /** bagTimeoutSeconds: 약봉투는 OCR과 Claude를 두 번까지 거쳐 알약보다 오래 걸린다. */
    public record Pill(String baseUrl, String internalKey, int timeoutSeconds, int bagTimeoutSeconds) {
    }

    /** baseUrl은 시험용으로 바꿀 때만 쓴다. 비우면 공공데이터포털 주소. */
    public record Mfds(String serviceKey, String serviceKeyFile, String baseUrl) {
    }

    /** firebaseProjectId를 비우면 로그인 확인을 하지 않는다 (혼자 개발·시험할 때). */
    public record Auth(String firebaseProjectId, String firebaseJwksUrl) {

        public boolean enabled() {
            return firebaseProjectId != null && !firebaseProjectId.isBlank();
        }
    }
}
