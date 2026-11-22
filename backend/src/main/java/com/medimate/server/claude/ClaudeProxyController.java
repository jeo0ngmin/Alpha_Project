package com.medimate.server.claude;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medimate.server.common.ApiException;
import com.medimate.server.common.FirebaseAuthFilter;
import com.medimate.server.common.Limits;
import com.medimate.server.common.MedimateProperties;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 영양제 챗봇의 Claude 호출 중계 (앱 README 8-6). 키를 앱(APK)에 넣지 않기 위해 둔다.
 * 앱의 anthropic-java SDK가 baseUrl을 "{서버}/claude"로 두면 Messages API 요청이 그대로 온다.
 * 허용한 모델·최대 토큰 안에서만, 사용자(또는 IP)당 시간당 횟수 제한을 두고 서버의 키로 보낸다.
 * 앱이 보낸 x-api-key는 버린다. 대화 본문은 환자 정보라서 로그에 남기지 않는다.
 * 로그인 확인(FIREBASE_PROJECT_ID)이 꺼져 있으면 이 PC(에뮬레이터 포함)에서 온 요청만 받는다.
 */
@RestController
public class ClaudeProxyController {

    private static final Logger log = LoggerFactory.getLogger(ClaudeProxyController.class);
    private static final URI DEFAULT_ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
    private static final String DEFAULT_VERSION = "2023-06-01";
    private static final String TTS_UNAVAILABLE = "상담 기능을 지금 쓸 수 없어요. 잠시 후 다시 시도해 주세요.";

    private final ObjectMapper mapper;
    private final Limits limits;
    private final boolean authEnabled;
    private final String apiKey;
    private final Set<String> allowedModels;
    private final int maxTokens;
    private final int hourly;
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Autowired
    public ClaudeProxyController(MedimateProperties props, ObjectMapper mapper, Limits limits) {
        this(props, mapper, limits, DEFAULT_ENDPOINT);
    }

    /** endpoint는 테스트에서 가짜 Anthropic 서버로 바꿀 때만 쓴다 */
    ClaudeProxyController(MedimateProperties props, ObjectMapper mapper, Limits limits, URI endpoint) {
        MedimateProperties.Claude cfg = props.claude();
        this.mapper = mapper;
        this.limits = limits;
        this.authEnabled = props.auth() != null && props.auth().enabled();
        this.apiKey = cfg == null ? null : resolveKey(cfg.apiKey(), cfg.apiKeyFile());
        this.allowedModels = cfg == null || cfg.allowedModels() == null ? Set.of()
                : Arrays.stream(cfg.allowedModels().split(",")).map(String::trim)
                        .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        this.maxTokens = cfg == null ? 0 : cfg.maxTokens();
        this.hourly = cfg == null ? 0 : cfg.hourly();
        this.endpoint = endpoint;
        if (apiKey == null) {
            log.info("Claude 키 없음: 챗봇 중계(/claude/v1/messages)는 503을 돌려줍니다.");
        }
    }

    @PostMapping(path = "/claude/v1/messages")
    public ResponseEntity<String> messages(HttpServletRequest request,
                                           @RequestHeader(value = "anthropic-version", required = false) String version,
                                           @RequestHeader(value = "anthropic-beta", required = false) String beta,
                                           @RequestBody String body) {
        if (!authEnabled && !isLocal(request.getRemoteAddr())) {
            throw ApiException.forbidden("로그인 확인이 꺼져 있어 이 PC에서 온 요청만 받습니다.");
        }
        if (apiKey == null) {
            throw ApiException.retryable(HttpStatus.SERVICE_UNAVAILABLE, "MM-CLD-5030",
                    "Claude 키가 설정되지 않았습니다.", TTS_UNAVAILABLE);
        }
        validate(body);
        limits.rate("claude:" + FirebaseAuthFilter.clientKey(request), hourly, Duration.ofHours(1));

        HttpRequest.Builder req = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(120))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", version == null || version.isBlank() ? DEFAULT_VERSION : version)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (beta != null && !beta.isBlank()) {
            req.header("anthropic-beta", beta);
        }
        try {
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                log.warn("Claude 응답 {}", res.statusCode());
            }
            // Anthropic의 응답(오류 포함)을 그대로 돌려줘야 앱의 SDK가 읽을 수 있다
            return ResponseEntity.status(res.statusCode()).contentType(MediaType.APPLICATION_JSON).body(res.body());
        } catch (IOException e) {
            log.warn("Claude 연결 실패: {}", e.getClass().getSimpleName());
            throw ApiException.retryable(HttpStatus.BAD_GATEWAY, "MM-CLD-5020", "Claude에 연결하지 못했습니다.",
                    TTS_UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.retryable(HttpStatus.BAD_GATEWAY, "MM-CLD-5020", "Claude 요청이 중단되었습니다.",
                    TTS_UNAVAILABLE);
        }
    }

    /** 스트리밍 없이, 허용한 모델과 최대 토큰 안에서만 */
    void validate(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("body", "JSON 형식이 아닙니다.");
        }
        if (root == null || !root.isObject()) {
            throw ApiException.badRequest("body", "JSON 객체가 아닙니다.");
        }
        if (!allowedModels.contains(root.path("model").asText(""))) {
            throw ApiException.forbidden("허용하지 않은 모델입니다.");
        }
        long tokens = root.path("max_tokens").asLong(0);
        if (tokens <= 0 || tokens > maxTokens) {
            throw ApiException.forbidden("max_tokens가 허용 범위를 벗어났습니다.");
        }
        if (root.path("stream").asBoolean(false)) {
            throw ApiException.forbidden("스트리밍은 지원하지 않습니다.");
        }
    }

    static boolean isLocal(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** 환경변수 값이 있으면 그것, 없으면 키 파일의 첫 줄 ("NAME=값" 형식이면 값만) */
    static String resolveKey(String key, String keyFile) {
        if (key != null && !key.isBlank()) {
            return key.trim();
        }
        if (keyFile == null || keyFile.isBlank()) {
            return null;
        }
        try {
            Path file = Path.of(keyFile);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                int eq = t.indexOf('=');
                boolean named = eq > 0 && t.substring(0, eq).matches("[A-Z][A-Z0-9_]*");
                String value = (named ? t.substring(eq + 1) : t).trim().replaceAll("^[\"']|[\"']$", "");
                return value.isEmpty() ? null : value;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Claude 키 파일을 읽지 못했습니다: {}", e.getClass().getSimpleName());
        }
        return null;
    }
}
