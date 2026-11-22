package com.medimate.server.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medimate.server.common.ApiException;
import com.medimate.server.common.Limits;
import com.medimate.server.common.MedimateProperties;
import com.sun.net.httpserver.HttpServer;

class ClaudeProxyControllerTest {

    private static final String OK_BODY = "{\"model\":\"claude-opus-5-5\",\"max_tokens\":100,"
            + "\"messages\":[{\"role\":\"user\",\"content\":\"눈이 침침해요\"}]}";

    private HttpServer fake;
    private URI endpoint;
    private final AtomicReference<String> seenKey = new AtomicReference<>();
    private final AtomicReference<String> seenBeta = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();

    @BeforeEach
    void startFakeAnthropic() throws IOException {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/v1/messages", ex -> {
            seenKey.set(ex.getRequestHeaders().getFirst("x-api-key"));
            seenBeta.set(ex.getRequestHeaders().getFirst("anthropic-beta"));
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"type\":\"message\",\"content\":[]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        fake.start();
        endpoint = URI.create("http://127.0.0.1:" + fake.getAddress().getPort() + "/v1/messages");
    }

    @AfterEach
    void stop() {
        fake.stop(0);
    }

    private ClaudeProxyController controller(String key, String firebaseProject, int hourly) {
        MedimateProperties props = new MedimateProperties(null, null, null, null,
                new MedimateProperties.Auth(firebaseProject, null),
                new MedimateProperties.Claude(key, null, "claude-opus-5-5", 4096, hourly, null));
        return new ClaudeProxyController(props, new ObjectMapper(), new Limits(), endpoint);
    }

    private ClaudeProxyController bedrockController(String modelId) {
        MedimateProperties props = new MedimateProperties(null, null, null, null, null,
                new MedimateProperties.Claude("anthropic-key", null, "claude-opus-5-5", 4096, 60,
                        new MedimateProperties.Bedrock("bedrock-token", "ap-northeast-2", modelId)));
        return new ClaudeProxyController(props, new ObjectMapper(), new Limits(), endpoint);
    }

    private static MockHttpServletRequest request(String remote) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/claude/v1/messages");
        req.setRemoteAddr(remote);
        return req;
    }

    @Test
    void forwardsWithServerKeyAndReturnsAnthropicResponse() {
        ResponseEntity<String> res = controller("server-key", null, 60)
                .messages(request("127.0.0.1"), null, "some-beta", OK_BODY);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).contains("\"type\":\"message\"");
        assertThat(seenKey.get()).isEqualTo("server-key");
        assertThat(seenBeta.get()).isEqualTo("some-beta");
        assertThat(seenBody.get()).isEqualTo(OK_BODY);
    }

    @Test
    void rejectsOtherModelsAndTooManyTokens() {
        ClaudeProxyController c = controller("server-key", null, 60);
        assertThatThrownBy(() -> c.messages(request("127.0.0.1"), null, null,
                OK_BODY.replace("claude-opus-5-5", "other-model")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> c.messages(request("127.0.0.1"), null, null,
                OK_BODY.replace("100", "999999")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(seenBody.get()).isNull();
    }

    @Test
    void withoutLoginCheckOnlyLocalRequestsAreAccepted() {
        assertThatThrownBy(() -> controller("server-key", null, 60)
                .messages(request("203.0.113.5"), null, null, OK_BODY))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(HttpStatus.FORBIDDEN));
        // 로그인 확인이 켜져 있으면 필터가 토큰을 확인하므로 외부 요청도 받는다
        ResponseEntity<String> res = controller("server-key", "medimate-test", 60)
                .messages(request("203.0.113.5"), null, null, OK_BODY);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void noKeyGives503AndRateLimitApplies() {
        assertThatThrownBy(() -> controller(null, null, 60).messages(request("127.0.0.1"), null, null, OK_BODY))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        ClaudeProxyController c = controller("server-key", null, 1);
        c.messages(request("127.0.0.1"), null, null, OK_BODY);
        assertThatThrownBy(() -> c.messages(request("127.0.0.1"), null, null, OK_BODY))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void readsKeyFromFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".anthropic_key");
        Files.writeString(file, "# 메모\nANTHROPIC_API_KEY=\"sk-test\"\n");
        assertThat(ClaudeProxyController.resolveKey(null, file.toString())).isEqualTo("sk-test");
        Files.writeString(file, "sk-plain\n");
        assertThat(ClaudeProxyController.resolveKey("", file.toString())).isEqualTo("sk-plain");
        assertThat(ClaudeProxyController.resolveKey(" env ", file.toString())).isEqualTo("env");
        assertThat(ClaudeProxyController.resolveKey(null, dir.resolve("없음").toString())).isNull();
    }

    @Test
    void bedrockUsesBedrockKeyPrefixedModelAndDropsFallbacks() throws IOException {
        String body = "{\"model\":\"claude-opus-5-5\",\"max_tokens\":100,\"fallbacks\":\"default\","
                + "\"output_config\":{\"effort\":\"low\"},\"messages\":[{\"role\":\"user\",\"content\":\"피곤해요\"}]}";
        ResponseEntity<String> res = bedrockController(null).messages(request("127.0.0.1"), null,
                "server-side-fallback-2026-07-01, other-beta", body);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(seenKey.get()).isEqualTo("bedrock-token");
        assertThat(seenBeta.get()).isEqualTo("other-beta");
        var sent = new ObjectMapper().readTree(seenBody.get());
        assertThat(sent.path("model").asText()).isEqualTo("anthropic.claude-opus-5-5");
        assertThat(sent.has("fallbacks")).isFalse();
        assertThat(sent.path("output_config").path("effort").asText()).isEqualTo("low");
        assertThat(sent.path("messages").get(0).path("content").asText()).isEqualTo("피곤해요");
    }

    @Test
    void bedrockModelIdOverrideAndBetaFilter() throws IOException {
        bedrockController("global.anthropic.claude-opus-5-5").messages(request("127.0.0.1"), null,
                "server-side-fallback-2026-07-01", OK_BODY);
        assertThat(new ObjectMapper().readTree(seenBody.get()).path("model").asText())
                .isEqualTo("global.anthropic.claude-opus-5-5");
        assertThat(seenBeta.get()).isNull();
        assertThat(ClaudeProxyController.withoutFallbackBeta(null)).isNull();
    }

    @Test
    void bedrockEndpointUsesRegion() {
        MedimateProperties props = new MedimateProperties(null, null, null, null, null,
                new MedimateProperties.Claude(null, null, "claude-opus-5-5", 4096, 60,
                        new MedimateProperties.Bedrock("t", "ap-northeast-2", null)));
        ClaudeProxyController c = new ClaudeProxyController(props, new ObjectMapper(), new Limits());
        assertThat(c).extracting("endpoint").hasToString(
                "https://bedrock-mantle.ap-northeast-2.api.aws/anthropic/v1/messages");
    }
}
