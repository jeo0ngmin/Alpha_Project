package com.medimate.server.pill;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medimate.server.common.MedimateProperties;

/**
 * 식약처 의약품 제품 허가정보(DrugPrdtPrmsnInfoService08). e약은요에 없는 약(주로 전문의약품)의
 * 효능효과·용법용량·사용상의 주의사항·저장방법 원문을 읽는다. 키는 e약은요와 같은 공공데이터 키를 쓰고 로그에 남기지 않는다.
 */
@Component
public class MfdsPermitClient {

    /** 허가 문서에서 뽑은 글. 없는 항목은 null */
    public record PermitInfo(String efficacy, String usage, String precaution, String storage) {
        public boolean isEmpty() {
            return efficacy == null && usage == null && precaution == null && storage == null;
        }
    }

    private static final String BASE =
            "https://apis.data.go.kr/1471000/DrugPrdtPrmsnInfoService08/getDrugPrdtPrmsnDtlInq08";
    private static final Pattern ARTICLE = Pattern.compile("<ARTICLE\\s+title=\"([^\"]*)\"[^>]*>(.*?)</ARTICLE>",
            Pattern.DOTALL);
    private static final Pattern PARAGRAPH = Pattern.compile("<PARAGRAPH[^>]*>(.*?)</PARAGRAPH>", Pattern.DOTALL);
    private static final Pattern CDATA = Pattern.compile("<!\\[CDATA\\[(.*?)]]>", Pattern.DOTALL);

    private final RestClient http;
    private final ObjectMapper mapper;
    private final String serviceKey;
    private final String baseUrl;

    @Autowired
    public MfdsPermitClient(MedimateProperties props, ObjectMapper mapper) {
        this(props, mapper, BASE);
    }

    MfdsPermitClient(MedimateProperties props, ObjectMapper mapper, String baseUrl) {
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.serviceKey = MfdsClient.resolveKey(props.mfds());
        var factory = new JdkClientHttpRequestFactory();
        // 주의사항 문서가 커서(수십 KB) e약은요보다 넉넉히 기다린다
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return serviceKey != null;
    }

    /** 허가정보에 없는 약이면 빈 값, 호출 실패면 MfdsClient.UnavailableException. */
    public Optional<PermitInfo> fetch(String itemSeq) {
        URI uri = URI.create(baseUrl + "?serviceKey=" + URLEncoder.encode(serviceKey, StandardCharsets.UTF_8)
                + "&item_seq=" + URLEncoder.encode(itemSeq, StandardCharsets.UTF_8) + "&type=json&numOfRows=1&pageNo=1");
        String body = null;
        Exception last = null;
        for (int attempt = 0; attempt < 2 && body == null; attempt++) {
            try {
                byte[] raw = http.get().uri(uri).retrieve().body(byte[].class);
                body = raw == null ? null : new String(raw, StandardCharsets.UTF_8);
            } catch (RuntimeException e) {
                last = e; // 예외 메시지에는 키가 든 URL이 있을 수 있어 클래스 이름만 남긴다
            }
        }
        if (body == null) {
            throw new MfdsClient.UnavailableException("허가정보 호출 실패: "
                    + (last == null ? "빈 응답" : last.getClass().getSimpleName()));
        }
        try {
            JsonNode items = mapper.readTree(body).path("body").path("items");
            JsonNode first = items.isArray() && !items.isEmpty() ? items.get(0)
                    : items.has("item") ? items.get("item") : null;
            if (first != null && first.isArray()) {
                first = first.isEmpty() ? null : first.get(0);
            }
            if (first == null || first.isMissingNode() || first.isNull()) {
                return Optional.empty();
            }
            PermitInfo info = new PermitInfo(docText(value(first, "EE_DOC_DATA")), docText(value(first, "UD_DOC_DATA")),
                    docText(value(first, "NB_DOC_DATA")), plain(value(first, "STORAGE_METHOD")));
            return info.isEmpty() ? Optional.empty() : Optional.of(info);
        } catch (Exception e) {
            throw new MfdsClient.UnavailableException("허가정보 응답 해석 실패: " + e.getClass().getSimpleName());
        }
    }

    /** 응답은 값이 없을 때 null 대신 "None" 글자를 주기도 한다 */
    private static String value(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText();
        return s.isBlank() || "None".equals(s.trim()) ? null : s;
    }

    /**
     * 허가 문서(XML 글자: DOC > SECTION > ARTICLE(title) > PARAGRAPH(CDATA))를 읽기 좋은 글로 바꾼다.
     * 항목 제목("1. 경고")은 한 줄로 두고, 문단은 줄을 바꿔 잇는다. 문단 안의 HTML 태그는 지운다.
     */
    public static String docText(String xml) {
        if (xml == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        Matcher article = ARTICLE.matcher(xml);
        boolean anyArticle = false;
        while (article.find()) {
            anyArticle = true;
            String title = plain(article.group(1));
            if (title != null) {
                lines.add(title);
            }
            addParagraphs(article.group(2), lines);
        }
        if (!anyArticle) {
            addParagraphs(xml, lines);
        }
        String text = String.join("\n", lines).trim();
        return text.isEmpty() ? null : text;
    }

    private static void addParagraphs(String xml, List<String> lines) {
        Matcher p = PARAGRAPH.matcher(xml);
        while (p.find()) {
            String inner = p.group(1);
            Matcher c = CDATA.matcher(inner);
            String text = plain(c.find() ? c.group(1) : inner);
            if (text != null) {
                lines.add(text);
            }
        }
    }

    /** HTML 태그·엔티티를 지우고 공백을 정리한다 */
    static String plain(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? null : t;
    }
}
