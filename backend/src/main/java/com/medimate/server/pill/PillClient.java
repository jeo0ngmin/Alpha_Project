package com.medimate.server.pill;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medimate.server.common.Enums.ColorClass;
import com.medimate.server.common.Enums.DrugShape;
import com.medimate.server.common.Enums.LineType;
import com.medimate.server.common.MedimateProperties;

/** pill-classifier 추론 서버 호출. 응답의 null 필드는 지워서 API 응답에 그대로 쓸 수 있게 한다. */
@Component
public class PillClient {

    /** 추론 서버가 꺼져 있거나 오류를 돌려준 경우. */
    public static class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String internalKey;
    private final Duration timeout;
    private final Duration bagTimeout;

    public PillClient(MedimateProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        String base = props.pill().baseUrl();
        this.baseUrl = base == null ? "" : base.replaceAll("/+$", "");
        this.internalKey = props.pill().internalKey();
        this.timeout = Duration.ofSeconds(props.pill().timeoutSeconds());
        this.bagTimeout = Duration.ofSeconds(props.pill().bagTimeoutSeconds() > 0 ? props.pill().bagTimeoutSeconds() : 150);
    }

    public JsonNode identify(byte[] front, byte[] back, String hintsJson, int topk) {
        String boundary = "medimate" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        filePart(body, boundary, "front", front);
        if (back != null) {
            filePart(body, boundary, "back", back);
        }
        if (hintsJson != null) {
            textPart(body, boundary, "hints", hintsJson);
        }
        textPart(body, boundary, "topk", Integer.toString(topk));
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = request("/v1/identify").timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return send(request).orElseThrow(() -> new Unavailable("식별 경로 없음 (404)"));
    }

    /** 약봉투 사진을 복약 정보 초안으로. ocrText를 주면 추론 서버는 CLOVA OCR을 건너뛴다. */
    public JsonNode recognizeBag(byte[] jpeg, String ocrText) {
        String boundary = "medimate" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        filePart(body, boundary, "image", jpeg);
        if (ocrText != null && !ocrText.isBlank()) {
            textPart(body, boundary, "ocrText", ocrText);
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = request("/v1/bag/recognize").timeout(bagTimeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return send(request).orElseThrow(() -> new Unavailable("약봉투 인식 경로 없음 (404). 추론 서버를 최신으로 다시 켜세요"));
    }

    public Optional<JsonNode> item(String itemSeq) {
        String path = "/v1/items/" + URLEncoder.encode(itemSeq, StandardCharsets.UTF_8);
        return send(request(path).timeout(Duration.ofSeconds(10)).GET().build());
    }

    public JsonNode search(DrugShape shape, List<ColorClass> colors, String printText, LineType lineFront, String itemName,
                           long offset, int limit) {
        List<String> query = new ArrayList<>();
        add(query, "drugShape", shape == null ? null : shape.name());
        if (colors != null) {
            colors.forEach(c -> add(query, "colorClass", c.name()));
        }
        add(query, "printText", printText);
        add(query, "lineFront", lineFront == null ? null : lineFront.name());
        add(query, "itemName", itemName);
        add(query, "offset", Long.toString(offset));
        add(query, "limit", Integer.toString(limit));
        HttpRequest request = request("/v1/items?" + String.join("&", query)).timeout(Duration.ofSeconds(15)).GET().build();
        return send(request).orElseThrow(() -> new Unavailable("검색 경로 없음 (404)"));
    }

    private static void add(List<String> query, String name, String value) {
        if (value != null && !value.isBlank()) {
            query.add(name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
    }

    private HttpRequest.Builder request(String path) {
        if (baseUrl.isEmpty()) {
            throw new Unavailable("PILL_BASE_URL이 비어 있음");
        }
        HttpRequest.Builder b;
        try {
            b = HttpRequest.newBuilder(URI.create(baseUrl + path));
        } catch (IllegalArgumentException e) {
            throw new Unavailable("PILL_BASE_URL 형식 오류");
        }
        if (internalKey != null && !internalKey.isBlank()) {
            b.header("X-Internal-Key", internalKey);
        }
        return b;
    }

    /** 200이면 본문, 404면 빈 값, 그 밖에는 Unavailable. */
    private Optional<JsonNode> send(HttpRequest request) {
        try {
            HttpResponse<byte[]> r = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() == 404) {
                return Optional.empty();
            }
            if (r.statusCode() != 200) {
                throw new Unavailable("추론 서버 응답 " + r.statusCode());
            }
            return Optional.of(withoutNulls(mapper.readTree(r.body())));
        } catch (IOException e) {
            throw new Unavailable("추론 서버 연결 실패: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("추론 서버 호출 중단");
        }
    }

    static JsonNode withoutNulls(JsonNode node) {
        if (node instanceof ObjectNode o) {
            Iterator<JsonNode> values = o.elements();
            while (values.hasNext()) {
                JsonNode v = values.next();
                if (v.isNull()) {
                    values.remove();
                } else {
                    withoutNulls(v);
                }
            }
        } else if (node instanceof ArrayNode a) {
            a.forEach(PillClient::withoutNulls);
        }
        return node;
    }

    private static void filePart(ByteArrayOutputStream out, String boundary, String name, byte[] jpeg) {
        out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + name
                + ".jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(jpeg);
        out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void textPart(ByteArrayOutputStream out, String boundary, String name, String value) {
        out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
}
