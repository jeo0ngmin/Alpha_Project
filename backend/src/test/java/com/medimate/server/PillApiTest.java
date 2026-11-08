package com.medimate.server;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medimate.server.common.Limits;
import com.medimate.server.common.Times;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** 앱이 쓰는 API 전체를 가짜 추론 서버와 함께 검증한다. 응답은 OpenAPI 명세와도 대조한다. */
@SpringBootTest
@AutoConfigureMockMvc
class PillApiTest {

    private static final String SPEC_FILE = "static/openapi.json";
    /** 응답만 검사한다. 요청은 일부러 틀리게 보내기도 하고, MockMvc의 multipart 본문은 검증기가 읽지 못한다. */
    private static final OpenApiInteractionValidator SPEC = OpenApiInteractionValidator.createForSpecificationUrl(SPEC_FILE)
            .withLevelResolver(LevelResolver.create()
                    .withLevel("validation.request", ValidationReport.Level.IGNORE)
                    // allOf로 합친 명세라 "추가 속성 금지"를 켜면 모든 응답이 걸린다
                    .withLevel("validation.response.body.schema.additionalProperties", ValidationReport.Level.IGNORE)
                    .build())
            .build();
    private static final HttpServer FAKE;
    private static volatile int identifyStatus = 200;
    private static volatile String identifyBody = "{}";
    private static volatile String lastIdentify = "";
    private static volatile String lastInternalKey;
    private static volatile String lastSearchQuery;
    private static volatile int itemsStatus = 200;
    private static volatile CountDownLatch holdIdentify;

    static {
        try {
            FAKE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        FAKE.createContext("/v1/identify", PillApiTest::identify);
        FAKE.createContext("/v1/items", PillApiTest::items);
        FAKE.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws IOException {
        r.add("medimate.pill.base-url", () -> "http://127.0.0.1:" + FAKE.getAddress().getPort());
        r.add("medimate.pill.internal-key", () -> "test-internal-key");
        r.add("medimate.storage.dir", Files.createTempDirectory("medimate-test").toString()::toString);
        r.add("medimate.mfds.service-key", () -> "");
        r.add("medimate.mfds.service-key-file", () -> "");
        r.add("medimate.limits.daily-pill", () -> 6);
        r.add("server.address", () -> "127.0.0.1");
    }

    @AfterAll
    static void stopFake() {
        FAKE.stop(0);
    }

    private static void identify(HttpExchange x) throws IOException {
        byte[] body = x.getRequestBody().readAllBytes();
        lastIdentify = new String(body, StandardCharsets.ISO_8859_1);
        lastInternalKey = x.getRequestHeaders().getFirst("X-Internal-Key");
        CountDownLatch hold = holdIdentify;
        if (hold != null) {
            try {
                hold.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        reply(x, identifyStatus, identifyBody);
    }

    private static void items(HttpExchange x) throws IOException {
        x.getRequestBody().readAllBytes();
        String path = x.getRequestURI().getPath();
        if (itemsStatus != 200) {
            reply(x, itemsStatus, "{}");
        } else if (path.equals("/v1/items")) {
            lastSearchQuery = x.getRequestURI().getQuery();
            reply(x, 200, """
                    {"items":[{"itemSeq":"A1","itemName":"가정","entpName":"회사","className":null,"drugShape":"ROUND","colorClass1":"WHITE","itemImageUrl":null}],
                     "total":25,"nextOffset":20}""");
        } else if (path.endsWith("/A1")) {
            reply(x, 200, """
                    {"itemSeq":"A1","itemName":"가정500mg","entpName":"회사","dosageForm":"필름코팅정","drugShape":"ROUND",
                     "colorClass1":"WHITE","printFront":null,"itemImageUrl":null,"itemPermitDate":"2020-01-02",
                     "appearance":{"drugShape":"ROUND","colorClass1":"WHITE","colorClass2":null,"lengLong":9.0,"featureConfidence":null}}""");
        } else if (path.endsWith("/C1")) {
            reply(x, 200, """
                    {"itemSeq":"C1","itemName":"나캡슐","dosageForm":"경질캡슐제","drugShape":"CAPSULE","appearance":{"drugShape":"CAPSULE"}}""");
        } else {
            reply(x, 404, "{}");
        }
    }

    private static void reply(HttpExchange x, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }

    private static String completed(boolean low, double first, double second, String issues) {
        return """
                {"status":"COMPLETED","reason":null,"pillCount":1,"lowConfidence":%s,"imageIssues":%s,
                 "features":{"drugShape":"ROUND","colorClass1":"WHITE","colorClass2":null,"printFront":"AB","printBack":null,
                             "lineFront":null,"lengLong":null,"lengShort":null,"thick":null,"featureConfidence":0.8},
                 "candidates":[
                   {"itemSeq":"A1","itemName":"가정500밀리그램(아세트아미노펜)","entpName":"회사","className":"해열제","etcOtcName":"일반의약품","itemImageUrl":"https://example.com/a.jpg","score":%s,"scoreBreakdown":{}},
                   {"itemSeq":"B2","itemName":"나정","entpName":null,"className":null,"etcOtcName":null,"itemImageUrl":null,"score":%s,"scoreBreakdown":{}}]}"""
                .formatted(low, issues, first, second);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    Limits limits;
    @Autowired
    RequestMappingHandlerMapping mappings;

    @BeforeEach
    void reset() {
        limits.resetForTest();
        identifyStatus = 200;
        itemsStatus = 200;
        holdIdentify = null;
        lastIdentify = "";
        lastInternalKey = null;
    }

    @AfterEach
    void restoreClock() {
        Times.setClockForTest(null);
    }

    // ── 사진 업로드 ────────────────────────────────────────────────

    /** 또렷한 각인이 있는 알약 사진을 흉내 낸다. */
    private static BufferedImage pillPhoto(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(70, 80, 90));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(235, 235, 225));
        g.fillOval(w / 3, h / 3, w / 3, h / 3);
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, h / 10));
        g.drawString("AB 12", w / 3 + 10, h / 2);
        g.dispose();
        return image;
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    private static MockMultipartFile part(byte[] bytes) {
        return new MockMultipartFile("file", "pill.jpg", "image/jpeg", bytes);
    }

    private ResultActions upload(byte[] bytes) throws Exception {
        return mvc.perform(multipart("/files/images").file(part(bytes)).param("purpose", "PILL"));
    }

    private JsonNode uploaded() throws Exception {
        String body = upload(jpeg(pillPhoto(600, 400))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    @Test
    void 업로드하면_서명_URL로_내려받을_수_있다() throws Exception {
        ResultActions result = upload(jpeg(pillPhoto(600, 400))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.fileId").isNotEmpty())
                .andExpect(jsonPath("$.data.purpose").value("PILL"))
                .andExpect(jsonPath("$.data.mimeType").value("image/jpeg"))
                .andExpect(jsonPath("$.data.width").value(600));
        result.andExpect(openApi().isValid(SPEC));
        JsonNode data = json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
        String path = data.path("signedUrl").asText().replaceFirst("^https?://[^/]+", "");

        mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getContentType()).isEqualTo("image/jpeg"))
                .andExpect(r -> assertThat(ImageIO.read(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()))).isNotNull());
    }

    @Test
    void 서명이_틀리거나_기한이_지나면_사진을_주지_않는다() throws Exception {
        JsonNode data = uploaded();
        String path = data.path("signedUrl").asText().replaceFirst("^https?://[^/]+", "");

        mvc.perform(get(path + "x")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MM-FIL-4030"));
        mvc.perform(get("/files/images/" + data.path("fileId").asText() + "/content?exp=99999999999&sig=abc"))
                .andExpect(status().isForbidden());

        Times.setClockForTest(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(11)));
        mvc.perform(get(path)).andExpect(status().isForbidden());
    }

    /** SOI 뒤에 방향값(6: 시계 방향 90도)과 카메라 정보가 든 EXIF를 끼워 넣는다. */
    private static byte[] withExifOrientation6(byte[] jpeg) {
        byte[] tiff = {'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0};
        byte[] header = {'E', 'x', 'i', 'f', 0, 0};
        int length = 2 + header.length + tiff.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 255);
        out.writeBytes(header);
        out.writeBytes(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    @Test
    void EXIF_방향을_적용하고_메타데이터를_지운다() throws Exception {
        ResultActions result = upload(withExifOrientation6(jpeg(pillPhoto(600, 400)))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.width").value(400))
                .andExpect(jsonPath("$.data.height").value(600));
        JsonNode data = json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
        String path = data.path("signedUrl").asText().replaceFirst("^https?://[^/]+", "");
        byte[] stored = mvc.perform(get(path)).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
    }

    @Test
    void 사진이_아니면_415() throws Exception {
        upload("<svg></svg>".getBytes(StandardCharsets.UTF_8)).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("MM-FIL-4150"))
                .andExpect(jsonPath("$.error.ttsMessage").isNotEmpty());
        // JPEG 머리만 붙인 깨진 파일
        upload(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5}).andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void 십MB를_넘으면_413() throws Exception {
        upload(new byte[10 * 1024 * 1024 + 1]).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("MM-FIL-4130"));
    }

    @Test
    void purpose가_없거나_틀리면_400() throws Exception {
        mvc.perform(multipart("/files/images").file(part(jpeg(pillPhoto(100, 100))))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/files/images").file(part(jpeg(pillPhoto(100, 100)))).param("purpose", "X"))
                .andExpect(status().isBadRequest());
    }

    private static BufferedImage flat(int gray) {
        BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(gray, gray, gray));
        g.fillRect(0, 0, 400, 300);
        g.dispose();
        return image;
    }

    @Test
    void 너무_어둡거나_밝거나_흐리면_422() throws Exception {
        upload(jpeg(flat(5))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("MM-FIL-4220"));
        upload(jpeg(flat(254))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("MM-FIL-4221"));
        upload(jpeg(flat(128))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("MM-FIL-4222"))
                .andExpect(jsonPath("$.error.ttsMessage").isNotEmpty());
    }

    // ── 식별 ──────────────────────────────────────────────────────

    private ResultActions request(String body) throws Exception {
        return mvc.perform(post("/pill-identification").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String submit(String extra) throws Exception {
        String fileId = uploaded().path("fileId").asText();
        ResultActions accepted = request("{\"fileId\":\"" + fileId + "\"" + extra + "}").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.type").value("PILL_IDENTIFICATION"))
                .andExpect(openApi().isValid(SPEC));
        return json.readTree(accepted.andReturn().getResponse().getContentAsString()).path("data").path("jobId").asText();
    }

    private JsonNode finished(String jobId) {
        return await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20)).until(() -> {
            String body = mvc.perform(get("/pill-identification/" + jobId)).andExpect(status().isOk())
                    .andExpect(openApi().isValid(SPEC)).andReturn().getResponse().getContentAsString();
            return json.readTree(body).path("data");
        }, d -> !d.path("status").asText().equals("PROCESSING"));
    }

    @Test
    void 확신이_있으면_대표_결과와_후보를_준다() throws Exception {
        identifyBody = completed(false, 0.9, 0.4, "[]");
        JsonNode d = finished(submit(""));

        assertThat(d.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(d.path("requiresUserSelection").asBoolean()).isFalse();
        assertThat(d.has("fallbackAction")).isFalse();
        assertThat(d.path("topMatch").path("itemSeq").asText()).isEqualTo("A1");
        assertThat(d.path("topMatch").path("isInMyMedications").asBoolean()).isFalse();
        assertThat(d.path("candidates").get(1).path("rank").asInt()).isEqualTo(2);
        assertThat(d.path("candidates").get(0).path("matchScore").asDouble()).isEqualTo(0.9);
        assertThat(d.path("extractedFeatures").path("printFront").asText()).isEqualTo("AB");
        assertThat(d.path("extractedFeatures").has("lineFront")).isFalse();
        // 단위는 풀어 읽고, 괄호 안 성분은 읽지 않으며, 확인을 요구한다
        assertThat(d.path("ttsScript").asText()).contains("가정오백 밀리그램").doesNotContain("아세트아미노펜")
                .contains("약사에게 꼭 확인");
        assertThat(lastInternalKey).isEqualTo("test-internal-key");
        assertThat(lastIdentify).contains("name=\"front\"").doesNotContain("name=\"back\"");
    }

    @Test
    void 뒷면과_힌트를_추론_서버에_넘긴다() throws Exception {
        identifyBody = completed(false, 0.9, 0.4, "[]");
        String back = uploaded().path("fileId").asText();
        finished(submit(",\"backFileId\":\"" + back + "\",\"hints\":{\"drugShape\":\"ROUND\",\"colorClass\":[\"WHITE\"],\"printText\":\"AB\"}"));

        assertThat(lastIdentify).contains("name=\"back\"").contains("name=\"hints\"")
                .contains("\"drugShape\":\"ROUND\"").contains("\"printText\":\"AB\"");
    }

    @Test
    void 복용_중인_약이면_표시한다() throws Exception {
        // 복용 중인 약은 앱(Firestore)에 있으므로 식별 요청 때 앱이 목록을 보낸다
        identifyBody = completed(false, 0.9, 0.4, "[]");
        JsonNode d = finished(submit(",\"currentMedications\":[{\"itemSeq\":\"A1\",\"name\":\"가정\"}]"));
        assertThat(d.path("candidates").get(0).path("isInMyMedications").asBoolean()).isTrue();
        assertThat(d.path("candidates").get(0).has("myPlanId")).isFalse();
        assertThat(d.path("topMatch").path("isInMyMedications").asBoolean()).isTrue();
        assertThat(d.path("candidates").get(1).path("isInMyMedications").asBoolean()).isFalse();
    }

    @Test
    void 일이위_차이가_작으면_사용자가_고른다() throws Exception {
        identifyBody = completed(false, 0.70, 0.60, "[]");
        JsonNode d = finished(submit(""));

        assertThat(d.path("requiresUserSelection").asBoolean()).isTrue();
        assertThat(d.has("fallbackAction")).isFalse();
        assertThat(d.path("ttsScript").asText()).contains("두 개").contains("약사에게 꼭 확인");
    }

    @Test
    void 확신이_없으면_단정하지_않고_대안을_권한다() throws Exception {
        identifyBody = completed(true, 0.5, 0.1, "[]");
        JsonNode search = finished(submit(""));
        assertThat(search.path("requiresUserSelection").asBoolean()).isTrue();
        assertThat(search.path("fallbackAction").asText()).isEqualTo("MANUAL_SEARCH");
        assertThat(search.has("topMatch")).isFalse();
        assertThat(search.path("ttsScript").asText()).contains("확실하지 않습니다").contains("약사");

        identifyBody = completed(true, 0.5, 0.1, "[\"BLUR\"]");
        assertThat(finished(submit("")).path("fallbackAction").asText()).isEqualTo("RETAKE");
    }

    @Test
    void 알약을_못_찾으면_다시_찍게_한다() throws Exception {
        identifyBody = "{\"status\":\"NOT_FOUND\",\"reason\":\"NO_PILL\",\"pillCount\":0,\"candidates\":[],\"features\":null}";
        JsonNode none = finished(submit(""));
        assertThat(none.path("status").asText()).isEqualTo("NOT_FOUND");
        assertThat(none.path("fallbackAction").asText()).isEqualTo("RETAKE");
        assertThat(none.path("ttsScript").asText()).contains("찾지 못했습니다");

        identifyBody = "{\"status\":\"NOT_FOUND\",\"reason\":\"MULTIPLE_PILLS\",\"pillCount\":2,\"candidates\":[]}";
        assertThat(finished(submit("")).path("ttsScript").asText()).contains("여러 개");
    }

    @Test
    void 후보가_하나도_없으면_직접_찾게_한다() throws Exception {
        identifyBody = "{\"status\":\"COMPLETED\",\"candidates\":[],\"lowConfidence\":true,\"features\":{\"drugShape\":\"ROUND\"}}";
        JsonNode d = finished(submit(""));
        assertThat(d.path("status").asText()).isEqualTo("NOT_FOUND");
        assertThat(d.path("fallbackAction").asText()).isEqualTo("MANUAL_SEARCH");
    }

    @Test
    void 추론_서버가_실패하면_FAILED와_수동_검색() throws Exception {
        identifyStatus = 500;
        JsonNode d = finished(submit(""));
        assertThat(d.path("status").asText()).isEqualTo("FAILED");
        assertThat(d.path("fallbackAction").asText()).isEqualTo("MANUAL_SEARCH");
        assertThat(d.path("ttsScript").asText()).isNotEmpty();
    }

    @Test
    void 없는_사진이나_잘못된_ID는_404() throws Exception {
        request("{\"fileId\":\"f_00000000000000000000000000\"}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MM-FIL-4040"));
        request("{\"fileId\":\"../../secret\"}").andExpect(status().isNotFound());
        request("{}").andExpect(status().isBadRequest());
        request("{\"fileId\":\"x\",\"hints\":{\"drugShape\":\"STAR\"}}").andExpect(status().isBadRequest());
        mvc.perform(get("/pill-identification/pid_NOPE")).andExpect(status().isNotFound());
    }

    @Test
    void 하루_한도를_넘으면_429() throws Exception {
        identifyBody = completed(false, 0.9, 0.4, "[]");
        String fileId = uploaded().path("fileId").asText();
        String body = "{\"fileId\":\"" + fileId + "\"}";
        for (int i = 0; i < 6; i++) {
            request(body).andExpect(status().isAccepted());
        }
        request(body).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.retryable").value(true))
                .andExpect(r -> assertThat(r.getResponse().getHeader("Retry-After")).isNotNull());
    }

    @Test
    void 결과_보관_기간이_지나면_410() throws Exception {
        identifyBody = completed(false, 0.9, 0.4, "[]");
        String jobId = submit("");
        finished(jobId);
        Times.setClockForTest(Clock.offset(Clock.systemUTC(), Duration.ofHours(25)));
        mvc.perform(get("/pill-identification/" + jobId)).andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("MM-PIL-4100"));
    }

    // ── 후보 확정 ─────────────────────────────────────────────────

    private ResultActions select(String jobId, String itemSeq) throws Exception {
        return mvc.perform(post("/pill-identification/" + jobId + "/select").contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemSeq\":\"" + itemSeq + "\"}"));
    }

    @Test
    void 후보를_고르면_약_정보와_계획_초안을_준다() throws Exception {
        identifyBody = completed(false, 0.9, 0.4, "[]");
        String jobId = submit("");
        finished(jobId);

        select(jobId, "A1").andExpect(status().isOk())
                .andExpect(openApi().isValid(SPEC))
                .andExpect(jsonPath("$.data.medication.itemSeq").value("A1"))
                .andExpect(jsonPath("$.data.medication.itemPermitDate").value("2020-01-02"))
                .andExpect(jsonPath("$.data.medication.appearance.lengLong").value(9.0))
                .andExpect(jsonPath("$.data.medication.printFront").doesNotExist())
                .andExpect(jsonPath("$.data.draftPlan.itemSeq").value("A1"))
                .andExpect(jsonPath("$.data.draftPlan.dosageUnit").value("TABLET"))
                .andExpect(jsonPath("$.data.draftPlan.frequencyPerDay").value(1))
                .andExpect(jsonPath("$.data.draftPlan.timeSlots[0].slot").value("MORNING"));
        assertThat(finished(jobId).path("status").asText()).isEqualTo("SELECTED");

        // 잘못 골랐으면 다시 고를 수 있고, 캡슐은 단위가 다르다
        select(jobId, "C1").andExpect(status().isOk()).andExpect(jsonPath("$.data.draftPlan.dosageUnit").value("CAPSULE"));
    }

    @Test
    void 식별에_실패해도_검색으로_찾은_약을_고를_수_있다() throws Exception {
        identifyStatus = 500;
        String jobId = submit("");
        finished(jobId);
        select(jobId, "A1").andExpect(status().isOk());
    }

    @Test
    void 잘못된_선택은_거절한다() throws Exception {
        identifyBody = completed(false, 0.9, 0.4, "[]");
        String jobId = submit("");
        finished(jobId);
        select(jobId, "NOPE").andExpect(status().isNotFound());
        select(jobId, "..%2F..%2Fx").andExpect(status().isBadRequest());
        select("pid_NOPE", "A1").andExpect(status().isNotFound());
        itemsStatus = 500;
        select(jobId, "A1").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("MM-PIL-5020"))
                .andExpect(jsonPath("$.error.retryable").value(true));
    }

    @Test
    void 식별_중에는_고를_수_없다() throws Exception {
        identifyBody = completed(false, 0.9, 0.4, "[]");
        holdIdentify = new CountDownLatch(1);
        String jobId = submit("");
        select(jobId, "A1").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MM-PIL-4090"));
        holdIdentify.countDown();
        finished(jobId);
    }

    // ── 수동 검색 ─────────────────────────────────────────────────

    @Test
    void 검색은_조건을_넘기고_커서로_이어진다() throws Exception {
        ResultActions first = mvc.perform(get("/pill-identification/search").param("drugShape", "ROUND")
                        .param("colorClass", "WHITE", "YELLOW").param("printText", "A B").param("lineFront", "PLUS")
                        .param("itemName", "타이").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(SPEC))
                .andExpect(jsonPath("$.data.items[0].itemSeq").value("A1"))
                .andExpect(jsonPath("$.data.items[0].className").doesNotExist())
                .andExpect(jsonPath("$.data.pageInfo.totalCount").value(25))
                .andExpect(jsonPath("$.data.pageInfo.hasNext").value(true));
        assertThat(lastSearchQuery).contains("drugShape=ROUND").contains("colorClass=WHITE").contains("colorClass=YELLOW")
                .contains("printText=A+B").contains("lineFront=PLUS").contains("itemName=타이").contains("offset=0").contains("limit=20");

        String cursor = json.readTree(first.andReturn().getResponse().getContentAsString())
                .path("data").path("pageInfo").path("nextCursor").asText();
        mvc.perform(get("/pill-identification/search").param("cursor", cursor)).andExpect(status().isOk());
        assertThat(lastSearchQuery).contains("offset=20");
    }

    @Test
    void 검색_입력이_틀리면_400이고_서버가_죽어_있으면_502() throws Exception {
        mvc.perform(get("/pill-identification/search").param("drugShape", "STAR")).andExpect(status().isBadRequest());
        mvc.perform(get("/pill-identification/search").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/pill-identification/search").param("cursor", "!!")).andExpect(status().isBadRequest());
        itemsStatus = 500;
        mvc.perform(get("/pill-identification/search")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.ttsMessage").isNotEmpty());
    }

    // ── 명세 ──────────────────────────────────────────────────────

    @Test
    void 구현과_명세의_경로가_정확히_일치한다() throws Exception {
        JsonNode spec = json.readTree(new ClassPathSpec().read());
        Set<String> documented = new TreeSet<>();
        spec.path("paths").fields().forEachRemaining(p -> p.getValue().fieldNames()
                .forEachRemaining(m -> documented.add(m.toUpperCase() + " " + p.getKey())));

        Set<String> implemented = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, method) -> {
            if (isOurs(method)) {
                List<String> paths = new ArrayList<>(info.getPatternValues());
                info.getMethodsCondition().getMethods().forEach(m -> paths.forEach(p -> implemented.add(m.name() + " " + p)));
            }
        });
        assertThat(implemented).isEqualTo(documented);
    }

    private static boolean isOurs(HandlerMethod method) {
        return method.getBeanType().getName().startsWith("com.medimate.server");
    }

    private static final class ClassPathSpec {
        byte[] read() throws IOException {
            try (var in = PillApiTest.class.getClassLoader().getResourceAsStream(SPEC_FILE)) {
                return in.readAllBytes();
            }
        }
    }

    @Test
    void 문서_화면이_열린다() throws Exception {
        mvc.perform(get("/docs")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/openapi.json")).andExpect(status().isOk());
        mvc.perform(get("/없는주소")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("MM-COM-4040"));
    }

}
