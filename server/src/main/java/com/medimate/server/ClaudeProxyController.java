package com.medimate.server;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Claude 호출 중계 (README 8-6, 10-3 C "Claude 호출 서버 이전").
 * 앱의 anthropic-java SDK가 baseUrl을 "{서버}/claude"로 두면 이 주소로 요청이 온다.
 * 로그인한 Firebase 사용자만, 허용한 모델·토큰 수 안에서, 시간당 횟수 제한을 두고 서버의 키로 보낸다.
 * 앱이 보낸 x-api-key는 버린다. 대화 본문은 환자 정보라서 로그에 남기지 않는다.
 */
@RestController
public class ClaudeProxyController {

    private static final Logger log = LoggerFactory.getLogger(ClaudeProxyController.class);
    private static final URI ANTHROPIC = URI.create("https://api.anthropic.com/v1/messages");
    private static final String DEFAULT_VERSION = "2023-06-01";

    private final FirebaseSetup firebase;
    private final ObjectMapper json;
    private final String apiKey;
    private final Set<String> allowedModels;
    private final long maxTokens;
    private final RateLimiter limiter;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ClaudeProxyController(FirebaseSetup firebase, ObjectMapper json,
                                 @Value("${medimate.claude-api-key}") String apiKey,
                                 @Value("${medimate.claude-allowed-models}") String allowedModels,
                                 @Value("${medimate.claude-max-tokens}") long maxTokens,
                                 @Value("${medimate.claude-requests-per-hour}") int perHour) {
        this.firebase = firebase;
        this.json = json;
        this.apiKey = apiKey.trim();
        this.allowedModels = Arrays.stream(allowedModels.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        this.maxTokens = maxTokens;
        this.limiter = new RateLimiter(perHour, Clock.systemUTC());
    }

    boolean hasKey() {
        return !apiKey.isEmpty();
    }

    @PostMapping(path = "/claude/v1/messages")
    public ResponseEntity<String> messages(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "anthropic-version", required = false) String version,
            @RequestHeader(value = "anthropic-beta", required = false) String beta,
            @RequestBody String body) {
        String uid = firebase.verifyBearer(authorization);
        if (!hasKey()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "claude key not configured");
        }
        validate(body);
        if (!limiter.tryAcquire(uid)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate limited");
        }

        HttpRequest.Builder req = HttpRequest.newBuilder(ANTHROPIC)
                .timeout(Duration.ofSeconds(120))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", version == null || version.isBlank() ? DEFAULT_VERSION : version)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (beta != null && !beta.isBlank()) {
            req.header("anthropic-beta", beta);
        }
        try {
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("Claude 응답 {}", res.statusCode());
            }
            return ResponseEntity.status(res.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(res.body());
        } catch (IOException e) {
            log.warn("Claude 연결 실패: {}", e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "claude unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "interrupted");
        }
    }

    /** 스트리밍 없이, 허용한 모델과 최대 토큰 안에서만 */
    void validate(String body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid json");
        }
        if (root == null || !root.isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid json");
        }
        if (!allowedModels.contains(root.path("model").asString(""))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "model not allowed");
        }
        long tokens = root.path("max_tokens").asLong(0);
        if (tokens <= 0 || tokens > maxTokens) {
            throw new ApiException(HttpStatus.FORBIDDEN, "max_tokens not allowed");
        }
        if (root.path("stream").asBoolean(false)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "stream not supported");
        }
    }
}
