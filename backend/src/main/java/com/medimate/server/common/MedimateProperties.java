package com.medimate.server.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml의 medimate.* 설정. 비밀 값은 환경변수나 키 파일로만 들어온다. */
@ConfigurationProperties("medimate")
public record MedimateProperties(Storage storage, Limits limits, Pill pill, Mfds mfds, Auth auth, Claude claude) {

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

    /**
     * 영양제 챗봇의 Claude 호출 중계 (앱 README 8-6). 키는 apiKey(환경변수) 또는 apiKeyFile에서 읽는다.
     * allowedModels: 쉼표로 구분한 허용 모델. maxTokens: 요청 한 번의 최대 출력 토큰. hourly: 사용자(또는 IP)당 시간당 횟수.
     * bedrock: 값이 있으면 Anthropic 대신 Amazon Bedrock으로 보낸다.
     */
    public record Claude(String apiKey, String apiKeyFile, String allowedModels, int maxTokens, int hourly,
                         Bedrock bedrock) {
    }

    /**
     * Claude in Amazon Bedrock (bedrock-mantle). bearerToken(Bedrock API 키)이 있으면 Bedrock을 쓴다.
     * modelId를 비우면 앱이 보낸 모델 이름 앞에 "anthropic."을 붙인다 (claude-opus-5-5 → anthropic.claude-opus-5-5).
     */
    public record Bedrock(String bearerToken, String region, String modelId) {

        public boolean enabled() {
            return bearerToken != null && !bearerToken.isBlank();
        }
    }

    /** firebaseProjectId를 비우면 로그인 확인을 하지 않는다 (혼자 개발·시험할 때). */
    public record Auth(String firebaseProjectId, String firebaseJwksUrl) {

        public boolean enabled() {
            return firebaseProjectId != null && !firebaseProjectId.isBlank();
        }
    }
}
