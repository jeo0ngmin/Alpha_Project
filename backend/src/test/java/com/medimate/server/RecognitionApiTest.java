package com.medimate.server;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

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

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medimate.server.common.Limits;
import com.medimate.server.common.Times;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** 약봉투 인식 API: 가짜 추론 서버로 요청 → 결과 → 수정 → 확정(알림 일정 초안) 흐름을 검사한다. */
@SpringBootTest
@AutoConfigureMockMvc
class RecognitionApiTest {

    private static final OpenApiInteractionValidator SPEC = OpenApiInteractionValidator
            .createForSpecificationUrl("static/openapi.json")
            .withLevelResolver(LevelResolver.create()
                    .withLevel("validation.request", ValidationReport.Level.IGNORE)
                    .withLevel("validation.response.body.schema.additionalProperties", ValidationReport.Level.IGNORE)
                    .build())
            .build();
    private static final HttpServer FAKE;
    private static volatile int bagStatus = 200;
    private static volatile String bagBody = "{}";
    private static volatile String lastBag = "";

    static {
        try {
            FAKE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        FAKE.createContext("/v1/bag/recognize", x -> {
            lastBag = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            reply(x, bagStatus, bagBody);
        });
        FAKE.createContext("/mfds", x -> {
            x.getRequestBody().readAllBytes();
            String q = x.getRequestURI().getQuery();
            reply(x, 200, q != null && q.contains("itemSeq=1001")
                    ? "{\"body\":{\"items\":[{\"efcyQesitm\":\"<p>두통, 발열에 씁니다.</p>\",\"useMethodQesitm\":\"1회 1~2정 복용합니다.\","
                    + "\"atpnWarnQesitm\":\"매일 세 잔 이상 술을 마시는 사람은 의사와 상의하십시오.\",\"atpnQesitm\":null,"
                    + "\"intrcQesitm\":null,\"seQesitm\":\"발진이 나타날 수 있습니다.\",\"depositMethodQesitm\":\"실온에서 보관하십시오.\"}]}}"
                    : "{\"body\":{\"items\":[]}}");
        });
        FAKE.createContext("/v1/items", x -> {
            x.getRequestBody().readAllBytes();
            if (x.getRequestURI().getPath().endsWith("/2001")) {
                reply(x, 200, "{\"itemSeq\":\"2001\",\"itemName\":\"뮤코스타정(레바미피드)\",\"entpName\":\"한국오츠카제약\","
                        + "\"dosageForm\":\"필름코팅정\",\"drugShape\":\"ROUND\"}");
            } else {
                reply(x, 404, "{}");
            }
        });
        FAKE.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws IOException {
        r.add("medimate.pill.base-url", () -> "http://127.0.0.1:" + FAKE.getAddress().getPort());
        r.add("medimate.storage.dir", Files.createTempDirectory("medimate-rcg").toString()::toString);
        r.add("medimate.mfds.service-key", () -> "test-key");
        r.add("medimate.mfds.base-url", () -> "http://127.0.0.1:" + FAKE.getAddress().getPort() + "/mfds");
        r.add("medimate.mfds.service-key-file", () -> "");
        r.add("medimate.auth.firebase-project-id", () -> "");
    }

    @AfterAll
    static void stopFake() {
        FAKE.stop(0);
    }

    private static void reply(HttpExchange x, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }

    /** 추론 서버가 돌려주는 모양: 하나는 확인된 약, 하나는 식약처에서 못 찾아 확인이 필요한 약. */
    private static final String TWO_ITEMS = """
            {"status":"PARTIAL","documentType":"MEDICATION_BAG",
             "source":{"pharmacyName":"행복약국","hospitalName":null,"dispensedDate":"2026-11-05","patientNameMasked":"홍O동"},
             "items":[
              {"itemId":"i1","rawText":"타이레놀정500밀리그람","refinedName":"타이레놀정500밀리그램","matched":true,"itemSeq":"1001",
               "itemName":"타이레놀정500밀리그람(아세트아미노펜)","entpName":"한국얀센","dosageForm":"필름코팅정","strength":"500mg",
               "dosageAmount":1,"dosageUnit":"TABLET","frequencyPerDay":3,"timeSlots":["MORNING","LUNCH","DINNER"],
               "slotsInferred":false,"mealRelation":"AFTER_MEAL","mealOffsetMinutes":30,"durationDays":5,"totalQuantity":15,
               "confidence":0.96,"needsReview":false,"reviewReasons":[],"candidates":[],"matchGrade":"MATCHED",
               "cautions":["하루 4,000mg을 넘기지 마세요"]},
              {"itemId":"i2","rawText":"뮤코스타","refinedName":"뮤코스타","matched":false,"itemSeq":null,"itemName":null,
               "dosageAmount":1,"dosageUnit":null,"frequencyPerDay":3,"timeSlots":["MORNING","LUNCH","DINNER"],
               "slotsInferred":true,"mealRelation":"AFTER_MEAL","mealOffsetMinutes":30,"durationDays":5,
               "confidence":0.62,"needsReview":true,
               "reviewReasons":["비슷한 약이 여러 개라 골라야 합니다","복용 시간이 적혀 있지 않아 기본 시간으로 정했습니다"],
               "candidates":[{"itemSeq":"2001","itemName":"뮤코스타정(레바미피드)","entpName":"한국오츠카제약","similarity":0.8}]}],
             "generalInstructions":["물과 함께 드세요"],"generalCautions":["졸음이 올 수 있으니 운전을 피하세요"],"unreadable":[],"ocrConfidence":0.95,"overallConfidence":0.79,
             "processingMs":8123}""";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    Limits limits;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @BeforeEach
    void reset() {
        limits.resetForTest();
        bagStatus = 200;
        bagBody = TWO_ITEMS;
        // 2026-11-05 오전 9시(한국): 아침 8시는 지났고 점심 12시 반이 첫 복용이다
        Times.setClockForTest(Clock.fixed(OffsetDateTime.parse("2026-11-05T09:00:00+09:00").toInstant(), Times.KST));
    }

    @AfterEach
    void restoreClock() {
        Times.setClockForTest(null);
    }

    private String upload() throws Exception {
        BufferedImage image = new BufferedImage(600, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(225, 225, 220)); // 완전한 흰색은 과노출로 거절된다
        g.fillRect(0, 0, 600, 800);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
        g.drawString("Tylenol 500mg 1 3 5", 20, 200);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        String body = mvc.perform(multipart("/files/images").file(new MockMultipartFile("file", "bag.jpg", "image/jpeg",
                        out.toByteArray())).param("purpose", "MEDICATION_BAG"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("fileId").asText();
    }

    private String submit(String extra) throws Exception {
        String body = mvc.perform(post("/recognition/medication-bags").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":\"" + upload() + "\"" + extra + "}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.type").value("MEDICATION_BAG"))
                .andExpect(openApi().isValid(SPEC))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("jobId").asText();
    }

    private JsonNode finished(String jobId) {
        return await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20)).until(() -> {
            String body = mvc.perform(get("/recognition/jobs/" + jobId)).andExpect(status().isOk())
                    .andExpect(openApi().isValid(SPEC)).andReturn().getResponse().getContentAsString();
            return json.readTree(body).path("data");
        }, d -> !"PROCESSING".equals(d.path("status").asText()));
    }

    private ResultActions patchItem(String jobId, String itemId, String body) throws Exception {
        return mvc.perform(patch("/recognition/jobs/" + jobId + "/items/" + itemId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions confirm(String jobId, String body) throws Exception {
        return mvc.perform(post("/recognition/jobs/" + jobId + "/confirm").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void 약봉투를_읽어_목록과_음성_안내를_준다() throws Exception {
        JsonNode d = finished(submit(""));

        assertThat(d.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(d.path("reviewRequiredCount").asInt()).isEqualTo(1);
        assertThat(d.path("source").path("patientNameMasked").asText()).isEqualTo("홍O동");
        assertThat(d.path("items")).hasSize(2);
        assertThat(d.path("items").get(0).path("ttsScript").asText())
                .isEqualTo("첫 번째 약, 타이레놀정오백 밀리그램. 한 번에 한 알, 하루 세 번, 아침, 점심, 저녁 식후 삼십 분에 드세요. "
                        + "오 일 동안 드세요. 주의할 점입니다. 하루 사천 밀리그램을 넘기지 마세요.");
        assertThat(d.path("items").get(0).path("displayText").asText())
                .isEqualTo("1회 1정 · 하루 3회 · 아침, 점심, 저녁 · 식후 30분 · 5일분");
        assertThat(d.path("generalCautions").get(0).asText()).isEqualTo("졸음이 올 수 있으니 운전을 피하세요");
        // 약 상세 정보: 식약처에 있는 약은 네 항목에 내용이, 연결 안 된 약은 안내 문장만 있다
        JsonNode sections = d.path("items").get(0).path("sections");
        assertThat(d.path("items").get(0).path("infoStatus").asText()).isEqualTo("FOUND");
        assertThat(sections.get(0).path("title").asText()).isEqualTo("약 설명");
        assertThat(sections.get(0).path("text").asText()).isEqualTo("두통, 발열에 씁니다.");
        assertThat(sections.get(1).path("title").asText()).isEqualTo("주의사항");
        assertThat(sections.get(2).path("ttsText").asText()).isEqualTo("복용방법입니다. 한 번에 한 알에서 두 알 복용합니다.");
        assertThat(sections.get(3).path("title").asText()).isEqualTo("보관방법");
        assertThat(sections.get(4).path("title").asText()).isEqualTo("부작용");
        JsonNode unmatched = d.path("items").get(1);
        assertThat(unmatched.path("infoStatus").asText()).isEqualTo("NOT_MATCHED");
        assertThat(unmatched.path("sections").get(0).path("ttsText").asText()).contains("식약처 자료와 연결되지 않은 약");
        assertThat(d.path("items").get(1).path("ttsScript").asText())
                .contains("두 번째 약, 뮤코스타.").contains("복용 시간이 적혀 있지 않아 아침, 점심, 저녁으로 정했습니다").contains("가족이나 약사에게 확인해 주세요");
        assertThat(d.path("summaryTtsScript").asText()).startsWith("약 두 가지를 찾았습니다.")
                .contains("모든 약에 대한 주의할 점입니다. 졸음이 올 수 있으니 운전을 피하세요.")
                .contains("확인이 필요한 약이 한 가지 있습니다.").endsWith("'다시 찍기'를 누르세요.");
        assertThat(lastBag).contains("name=\"image\"").doesNotContain("name=\"ocrText\"");
    }

    @Test
    void 앱이_읽은_글자를_넘기면_그대로_전달한다() throws Exception {
        finished(submit(",\"ocrRawText\":\"타이레놀정 1정 1일 3회\""));
        assertThat(lastBag).contains("name=\"ocrText\"").contains("타이레놀정 1정 1일 3회");
    }

    @Test
    void 확인이_필요한_약이_있으면_확정할_수_없고_고치면_된다() throws Exception {
        String jobId = submit("");
        finished(jobId);

        confirm(jobId, "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MM-RCG-4091"))
                .andExpect(jsonPath("$.error.details[0].field").value("i2"))
                .andExpect(jsonPath("$.error.ttsMessage").value("확인이 필요한 약이 한 가지 있습니다. 먼저 확인하거나 목록에서 빼 주세요."));

        // 후보에서 약을 고르고 시간을 확인한다
        patchItem(jobId, "i2", "{\"itemSeq\":\"2001\",\"timeSlots\":[\"DINNER\",\"MORNING\"]}").andExpect(status().isOk())
                .andExpect(openApi().isValid(SPEC))
                .andExpect(jsonPath("$.data.itemName").value("뮤코스타정(레바미피드)"))
                .andExpect(jsonPath("$.data.matched").value(true))
                .andExpect(jsonPath("$.data.dosageUnit").value("TABLET"))
                .andExpect(jsonPath("$.data.timeSlots[0]").value("MORNING"))
                .andExpect(jsonPath("$.data.frequencyPerDay").value(2))
                .andExpect(jsonPath("$.data.needsReview").value(false))
                .andExpect(jsonPath("$.data.totalQuantity").value(10.0));
        assertThat(finished(jobId).path("status").asText()).isEqualTo("COMPLETED");

        String body = confirm(jobId, "{\"timeSlotOverrides\":{\"MORNING\":\"07:30\"}}").andExpect(status().isCreated())
                .andExpect(openApi().isValid(SPEC))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.alarmQuestion").value("알람으로 설정할까요?"))
                .andExpect(jsonPath("$.data.plans[0].itemSeq").value("1001"))
                .andExpect(jsonPath("$.data.plans[0].timeSlots[0].time").value("07:30"))
                .andExpect(jsonPath("$.data.plans[0].timeSlots[1].time").value("12:30"))
                .andExpect(jsonPath("$.data.plans[0].startDate").value("2026-11-05"))
                .andExpect(jsonPath("$.data.plans[0].endDate").value("2026-11-09"))
                .andExpect(jsonPath("$.data.plans[0].mealRelation").value("AFTER_MEAL"))
                .andExpect(jsonPath("$.data.plans[0].memo").value(
                        "주의: 하루 4,000mg을 넘기지 마세요 / 졸음이 올 수 있으니 운전을 피하세요 · 행복약국 약봉투 (2026-11-05 조제)"))
                .andExpect(jsonPath("$.data.plans[1].itemSeq").value("2001"))
                .andExpect(jsonPath("$.data.plans[1].frequencyPerDay").value(2))
                // 오늘 아침 7시 반은 지났다: 1번 약 15회 중 14회, 2번 약 10회 중 9회
                .andExpect(jsonPath("$.data.summaries[0].generatedDoseCount").value(14))
                .andExpect(jsonPath("$.data.summaries[0].itemId").value("i1"))
                .andExpect(jsonPath("$.data.summaries[1].itemName").value("뮤코스타정(레바미피드)"))
                .andExpect(jsonPath("$.data.createdPlans").doesNotExist())
                .andExpect(jsonPath("$.data.totalDoseCount").value(23))
                .andExpect(jsonPath("$.data.firstDoseAt").value("2026-11-05T12:30:00+09:00"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).path("data").path("ttsScript").asText())
                .isEqualTo("약 두 가지를 확정했습니다. 첫 복용은 오늘 오후 열두 시 삼십 분입니다. 알람으로 설정할까요?");

        // 같은 확정을 다시 보내도 결과가 같고, 확정 뒤에는 고칠 수 없다
        String again = confirm(jobId, "{}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(again).path("data")).isEqualTo(json.readTree(body).path("data"));
        patchItem(jobId, "i1", "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MM-RCG-4090"));

        // 서버는 일정을 저장하지 않는다. 앱이 보낸 "복용 중" 목록에 있는 약이면 이미 복용 중이라고 알린다
        String again2 = submit("");
        finished(again2);
        patchItem(again2, "i2", "{\"itemSeq\":\"2001\"}").andExpect(status().isOk());
        confirm(again2, "{\"currentMedications\":[{\"itemSeq\":\"2001\"}]}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MM-RCG-4094"))
                .andExpect(jsonPath("$.error.details[0].field").value("i2"));
        // 목록에 없으면 그대로 확정된다
        confirm(again2, "{\"currentMedications\":[{\"itemSeq\":\"9999\",\"name\":\"혈압약\"}]}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.plans.length()").value(2));
        assertThat(finished(jobId).path("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void 확인_없이_한꺼번에_확정할_수_있다() throws Exception {
        // 두 번째 약은 확인이 필요한 상태지만 값이 다 있어서 그대로 일정이 된다
        String jobId = submit("");
        finished(jobId);
        confirm(jobId, "{\"acceptAll\":true}").andExpect(status().isCreated())
                .andExpect(openApi().isValid(SPEC))
                .andExpect(jsonPath("$.data.plans.length()").value(2))
                .andExpect(jsonPath("$.data.skippedItems.length()").value(0));

        // 앱이 보낸 "복용 중" 목록에 있는 약은 빼고(이름으로도 비교), 남는 약이 없으면 알려 준다
        String partly = submit("");
        finished(partly);
        confirm(partly, "{\"acceptAll\":true,\"currentMedications\":[{\"itemSeq\":\"1001\"}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.plans.length()").value(1))
                .andExpect(jsonPath("$.data.skippedItems[0].itemId").value("i1"))
                .andExpect(jsonPath("$.data.skippedItems[0].reason").value("이미 복용 중인 약입니다"));
        String again = submit("");
        finished(again);
        confirm(again, "{\"acceptAll\":true,\"currentMedications\":[{\"itemSeq\":\"1001\"},{\"name\":\"뮤 코스타\"}]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("MM-RCG-4223"))
                .andExpect(jsonPath("$.error.details[0].reason").value(org.hamcrest.Matchers.endsWith(": 이미 복용 중인 약입니다")));
    }

    @Test
    void 읽지_못한_값이_있는_약은_빼고_만든다() throws Exception {
        bagBody = TWO_ITEMS.replaceFirst("\"durationDays\":5,\"totalQuantity\":15", "\"durationDays\":null");
        String jobId = submit("");
        finished(jobId);
        String body = confirm(jobId, "{\"acceptAll\":true}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.plans.length()").value(1))
                .andExpect(jsonPath("$.data.skippedItems[0].itemId").value("i1"))
                .andExpect(jsonPath("$.data.skippedItems[0].reason").value("복용량이나 복용 시간을 읽지 못했습니다"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).path("data").path("ttsScript").asText())
                .contains("타이레놀정오백 밀리그램은 일정에서 뺐습니다. 알람으로 설정할까요?");
    }

    @Test
    void 못_찾은_약은_빼고_확정할_수_있다() throws Exception {
        String jobId = submit("");
        finished(jobId);
        confirm(jobId, "{\"excludeItemIds\":[\"i2\"]}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.plans.length()").value(1));

        String other = submit("");
        finished(other);
        patchItem(other, "i1", "{\"excluded\":true}").andExpect(status().isOk());
        confirm(other, "{\"excludeItemIds\":[\"i2\"]}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("MM-RCG-4223"));
        confirm(other, "{\"excludeItemIds\":[\"i9\"]}").andExpect(status().isBadRequest());
    }

    @Test
    void 이름을_모르는_약은_읽은_이름으로_일정을_만든다() throws Exception {
        String jobId = submit("");
        finished(jobId);
        patchItem(jobId, "i2", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.needsReview").value(false));
        confirm(jobId, "{}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.plans[1].customName").value("뮤코스타"))
                .andExpect(jsonPath("$.data.plans[1].itemSeq").doesNotExist())
                .andExpect(jsonPath("$.data.plans[1].dosageUnit").value("TABLET"));
    }

    @Test
    void 잘못된_수정은_거절한다() throws Exception {
        String jobId = submit("");
        finished(jobId);
        patchItem(jobId, "i2", "{\"frequencyPerDay\":3,\"timeSlots\":[\"MORNING\"]}").andExpect(status().isBadRequest());
        patchItem(jobId, "i2", "{\"timeSlots\":[\"MORNING\",\"MORNING\"]}").andExpect(status().isBadRequest());
        patchItem(jobId, "i2", "{\"timeSlots\":[\"NOON\"]}").andExpect(status().isBadRequest());
        patchItem(jobId, "i2", "{\"frequencyPerDay\":5}").andExpect(status().isBadRequest());
        patchItem(jobId, "i2", "{\"dosageAmount\":0}").andExpect(status().isBadRequest());
        patchItem(jobId, "i2", "{\"mealRelation\":\"SOMETIME\"}").andExpect(status().isBadRequest());
        patchItem(jobId, "i2", "{\"itemSeq\":\"9999\"}").andExpect(status().isNotFound());
        patchItem(jobId, "i9", "{}").andExpect(status().isNotFound());
        confirm(jobId, "{\"timeSlotOverrides\":{\"MORNING\":\"25:00\"}}").andExpect(status().isBadRequest());
        confirm(jobId, "{\"startDate\":\"2026-11-01\"}").andExpect(status().isBadRequest());
        // 하루 횟수만 바꾸면 기본 시점으로 맞춘다
        patchItem(jobId, "i2", "{\"frequencyPerDay\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timeSlots.length()").value(1))
                .andExpect(jsonPath("$.data.timeSlots[0]").value("MORNING"));
    }

    @Test
    void 인식에_실패하면_이유와_음성_안내를_준다() throws Exception {
        bagBody = "{\"status\":\"FAILED\",\"error\":{\"code\":\"OCR_NO_TEXT\",\"message\":\"인식된 글자가 너무 적음\","
                + "\"ttsMessage\":\"글자가 거의 보이지 않습니다. 약봉투의 글씨 면이 보이게 찍어 주세요.\"},\"items\":[]}";
        String jobId = submit("");
        JsonNode d = finished(jobId);
        assertThat(d.path("status").asText()).isEqualTo("FAILED");
        assertThat(d.path("error").path("code").asText()).isEqualTo("OCR_NO_TEXT");
        assertThat(d.path("error").path("retryable").asBoolean()).isFalse();
        assertThat(d.path("summaryTtsScript").asText()).startsWith("글자가 거의 보이지 않습니다");
        confirm(jobId, "{}").andExpect(status().isConflict());

        bagStatus = 500;
        JsonNode down = finished(submit(""));
        assertThat(down.path("error").path("code").asText()).isEqualTo("RECOGNITION_UNAVAILABLE");
        assertThat(down.path("error").path("retryable").asBoolean()).isTrue();
    }

    @Test
    void 없는_작업과_만료된_작업() throws Exception {
        mvc.perform(get("/recognition/jobs/rcg_NOPE")).andExpect(status().isNotFound());
        mvc.perform(post("/recognition/medication-bags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileId\":\"f_00000000000000000000000000\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/recognition/medication-bags").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        String jobId = submit("");
        finished(jobId);
        Times.setClockForTest(Clock.fixed(OffsetDateTime.parse("2026-11-06T10:00:00+09:00").toInstant(), Times.KST));
        mvc.perform(get("/recognition/jobs/" + jobId)).andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("MM-RCG-4100"));
    }
}
