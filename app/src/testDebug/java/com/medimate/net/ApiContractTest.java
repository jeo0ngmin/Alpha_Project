package com.medimate.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.medimate.pill.Labels;
import com.medimate.pill.PillRepository;
import com.medimate.pill.model.ColorClass;
import com.medimate.pill.model.DrugShape;
import com.medimate.pill.model.FallbackAction;
import com.medimate.pill.model.JobAccepted;
import com.medimate.pill.model.PillHints;
import com.medimate.pill.model.PillIdentificationJob;
import com.medimate.pill.model.PillJobStatus;
import com.medimate.pill.model.SearchResult;
import com.medimate.pill.model.SelectResult;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * 백엔드 계약 테스트. 응답 본문은 실제 백엔드(backend/)에서 받아 저장한 것이다 (src/test/resources/fixtures).
 * 백엔드 응답 형식이 바뀌면 이 테스트가 먼저 깨진다.
 */
public class ApiContractTest {

    private MockWebServer server;
    private PillRepository repository;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        repository = new PillRepository(ApiClient.create(server.url("/").toString(), false));
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    public void uploadSendsMultipartWithPurpose() throws Exception {
        server.enqueue(json(201, "upload_201.json"));

        String fileId = repository.uploadPillPhoto(new byte[]{1, 2, 3}, "front.jpg");

        assertTrue(fileId.startsWith("f_"));
        RecordedRequest req = server.takeRequest();
        assertEquals("/files/images", req.getPath());
        String body = req.getBody().readUtf8();
        assertTrue(body.contains("name=\"file\"; filename=\"front.jpg\""));
        assertTrue(body.contains("Content-Type: image/jpeg"));
        assertTrue(body.contains("name=\"purpose\""));
        assertTrue(body.contains("PILL"));
    }

    @Test
    public void identificationUploadsBothSidesThenRequestsWithHints() throws Exception {
        server.enqueue(json(201, "upload_201.json"));
        server.enqueue(json(201, "upload_201.json"));
        server.enqueue(json(202, "job_accepted_202.json"));

        JobAccepted accepted = repository.requestIdentification(new byte[]{1}, new byte[]{2},
                new PillHints(DrugShape.ROUND, Arrays.asList(ColorClass.WHITE), "  DW "));

        assertTrue(accepted.jobId.startsWith("pid_"));
        assertEquals(1500, accepted.pollAfterMs);
        server.takeRequest();
        server.takeRequest();
        RecordedRequest create = server.takeRequest();
        assertEquals("/pill-identification", create.getPath());
        JsonObject sent = JsonParser.parseString(create.getBody().readUtf8()).getAsJsonObject();
        assertTrue(sent.get("fileId").getAsString().startsWith("f_"));
        assertTrue(sent.has("backFileId"));
        JsonObject hints = sent.getAsJsonObject("hints");
        assertEquals("ROUND", hints.get("drugShape").getAsString());
        assertEquals("WHITE", hints.getAsJsonArray("colorClass").get(0).getAsString());
        assertEquals("DW", hints.get("printText").getAsString()); // 앞뒤 공백 제거
    }

    @Test
    public void emptyHintsAreOmitted() throws Exception {
        server.enqueue(json(201, "upload_201.json"));
        server.enqueue(json(202, "job_accepted_202.json"));

        repository.requestIdentification(new byte[]{1}, null, new PillHints(null, null, " "));

        server.takeRequest();
        JsonObject sent = JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
        assertFalse(sent.has("hints"));
        assertFalse(sent.has("backFileId"));
    }

    @Test
    public void completedJobIsParsed() throws Exception {
        server.enqueue(json(200, "job_completed_200.json"));

        PillIdentificationJob job = repository.getJob("pid_x");

        assertEquals(PillJobStatus.COMPLETED, job.status);
        assertEquals("DEMO0002", job.topMatch.itemSeq);
        assertEquals(7, job.candidates.size());
        assertEquals(2, job.candidates.get(1).rank);
        assertEquals(58, job.candidates.get(1).matchPercent());
        assertFalse(job.candidates.get(0).isInMyMedications);
        assertNull(job.fallbackAction);
        assertEquals(DrugShape.ROUND, job.extractedFeatures.drugShape);
        assertEquals(ColorClass.YELLOW, job.extractedFeatures.colorClass1);
        assertEquals("노란색 원형", job.extractedFeatures.describe());
        assertTrue(job.ttsScript.startsWith("가장 비슷한 약은"));
    }

    @Test
    public void notFoundJobSuggestsRetake() throws Exception {
        server.enqueue(json(200, "job_not_found_200.json"));

        PillIdentificationJob job = repository.getJob("pid_x");

        assertEquals(PillJobStatus.NOT_FOUND, job.status);
        assertEquals(FallbackAction.RETAKE, job.fallbackAction);
        assertTrue(job.candidatesOrEmpty().isEmpty());
    }

    @Test
    public void selectReturnsMedicationAndDraftPlan() throws Exception {
        server.enqueue(json(200, "select_200.json"));

        SelectResult r = repository.select("pid_x", "DEMO0002");

        assertEquals("DEMO0002", r.medication.itemSeq);
        assertEquals("NONE", r.medication.appearance.lineFront);
        assertEquals("하루 1번, 아침 식후 1정", Labels.draftPlan(r.draftPlan));
        JsonObject sent = JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
        assertEquals("DEMO0002", sent.get("itemSeq").getAsString());
        assertFalse("내 약 추가는 앱이 Firestore에 직접 한다", sent.has("addToMedications"));
    }

    @Test
    public void searchSendsRepeatedColorParams() throws Exception {
        server.enqueue(json(200, "search_200.json"));

        SearchResult r = repository.search(DrugShape.ROUND, Arrays.asList(ColorClass.YELLOW, ColorClass.WHITE),
                null, "", null, 20);

        String path = server.takeRequest().getPath();
        assertEquals("/pill-identification/search?drugShape=ROUND&colorClass=YELLOW&colorClass=WHITE&size=20", path);
        assertEquals(1, r.items.size());
        assertTrue(r.pageInfo.hasNext);
        assertNotNull(r.pageInfo.nextCursor);
    }

    @Test
    public void qualityErrorCarriesTtsMessage() {
        server.enqueue(json(422, "upload_blurry_422.json"));
        try {
            repository.uploadPillPhoto(new byte[]{1}, "front.jpg");
            fail("422가 예외로 바뀌어야 한다");
        } catch (ApiException e) {
            assertEquals(422, e.httpStatus());
            assertEquals("MM-FIL-4220", e.error().code);
            assertTrue(e.error().ttsMessage.contains("흐립니다"));
            assertTrue(e.isRetakeNeeded());
            assertFalse(e.isRetryable());
        }
    }

    @Test
    public void nonJsonErrorBecomesUnexpected() {
        server.enqueue(new MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>"));
        try {
            repository.getJob("pid_x");
            fail();
        } catch (ApiException e) {
            assertEquals(ApiException.CODE_UNEXPECTED, e.error().code);
            assertTrue(e.isRetryable());
        }
    }

    @Test
    public void networkFailureIsRetryable() throws IOException {
        server.shutdown();
        try {
            repository.getJob("pid_x");
            fail();
        } catch (ApiException e) {
            assertEquals(ApiException.NO_RESPONSE, e.httpStatus());
            assertEquals(ApiException.CODE_NETWORK, e.error().code);
            assertTrue(e.isRetryable());
        }
    }

    private static MockResponse json(int status, String fixture) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(read(fixture));
    }

    static String read(String fixture) {
        try (InputStream in = ApiContractTest.class.getResourceAsStream("/fixtures/" + fixture)) {
            if (in == null) {
                throw new IllegalStateException("fixture 없음: " + fixture);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
