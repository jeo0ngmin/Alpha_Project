package com.medimate.server.pill;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medimate.server.common.MedimateProperties;

/** 식약처 e약은요(DrbEasyDrugInfoService). 서비스키는 로그에 남기지 않는다. */
@Component
public class MfdsClient {

    public record EasyInfo(String efficacy, String useMethod, String warning, String precaution, String interaction,
                           String sideEffect, String depositMethod) {
    }

    /** 일시 장애. 약이 e약은요에 없는 경우와 구별한다. */
    public static class UnavailableException extends RuntimeException {
        public UnavailableException(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(MfdsClient.class);
    private static final String BASE = "https://apis.data.go.kr/1471000/DrbEasyDrugInfoService/getDrbEasyDrugList";

    private final RestClient http;
    private final ObjectMapper mapper;
    private final String serviceKey;
    private final String baseUrl;

    @Autowired
    public MfdsClient(MedimateProperties props, ObjectMapper mapper) {
        this(props, mapper, props.mfds().baseUrl() == null || props.mfds().baseUrl().isBlank() ? BASE : props.mfds().baseUrl());
    }

    MfdsClient(MedimateProperties props, ObjectMapper mapper, String baseUrl) {
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.serviceKey = resolveKey(props.mfds());
        var factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(4));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** pill-classifier와 같은 키 파일을 함께 쓴다 ("NAME=값" 형식이어도 값만 읽는다). */
    static String resolveKey(MedimateProperties.Mfds cfg) {
        if (cfg.serviceKey() != null && !cfg.serviceKey().isBlank()) {
            return cfg.serviceKey().trim();
        }
        if (cfg.serviceKeyFile() == null || cfg.serviceKeyFile().isBlank()) {
            return null;
        }
        try {
            Path file = Path.of(cfg.serviceKeyFile());
            if (!Files.isRegularFile(file)) {
                return null;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                int eq = t.indexOf('=');
                // base64 키 끝의 = 패딩은 값의 일부다. "MFDS_KEY=값"처럼 대문자 이름이 앞에 붙은 형식만 잘라 낸다
                boolean named = eq > 0 && t.substring(0, eq).matches("[A-Z][A-Z0-9_]*") && !t.substring(eq + 1).matches("=*");
                String value = named ? t.substring(eq + 1) : t;
                return value.trim().replaceAll("^[\"']|[\"']$", "");
            }
        } catch (Exception e) {
            log.warn("공공데이터 키 파일을 읽지 못했습니다: {}", e.getClass().getSimpleName());
        }
        return null;
    }

    public boolean isConfigured() {
        return serviceKey != null;
    }

    /** 품목 상세. e약은요에 없는 약이면 빈 값, 호출 실패면 UnavailableException. */
    public Optional<EasyInfo> fetch(String itemSeq) {
        URI uri = URI.create(baseUrl + "?serviceKey=" + URLEncoder.encode(serviceKey, StandardCharsets.UTF_8)
                + "&itemSeq=" + URLEncoder.encode(itemSeq, StandardCharsets.UTF_8) + "&type=json&numOfRows=1&pageNo=1");
        String body = null;
        Exception last = null;
        for (int attempt = 0; attempt < 2 && body == null; attempt++) {
            try {
                // 공공데이터 응답은 charset을 밝히지 않는 경우가 있어 UTF-8로 직접 푼다
                byte[] raw = http.get().uri(uri).retrieve().body(byte[].class);
                body = raw == null ? null : new String(raw, StandardCharsets.UTF_8);
            } catch (RuntimeException e) {
                last = e; // 예외 메시지에는 키가 든 URL이 있을 수 있어 클래스 이름만 남긴다
            }
        }
        if (body == null) {
            throw new UnavailableException("e약은요 호출 실패: " + (last == null ? "빈 응답" : last.getClass().getSimpleName()));
        }
        try {
            JsonNode items = mapper.readTree(body).path("body").path("items");
            JsonNode first = items.isArray() && !items.isEmpty() ? items.get(0)
                    : items.has("item") ? items.get("item") : null;
            if (first == null || first.isMissingNode() || first.isNull()) {
                return Optional.empty();
            }
            return Optional.of(new EasyInfo(clean(first, "efcyQesitm"), clean(first, "useMethodQesitm"),
                    clean(first, "atpnWarnQesitm"), clean(first, "atpnQesitm"), clean(first, "intrcQesitm"),
                    clean(first, "seQesitm"), clean(first, "depositMethodQesitm")));
        } catch (Exception e) {
            throw new UnavailableException("e약은요 응답 해석 실패: " + e.getClass().getSimpleName());
        }
    }

    /** 원문의 HTML 태그와 줄바꿈을 정리한다. */
    private static String clean(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String text = n.asText().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? null : text;
    }
}
