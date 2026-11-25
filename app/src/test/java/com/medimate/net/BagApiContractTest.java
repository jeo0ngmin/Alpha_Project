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
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.medimate.bag.BagRepository;
import com.medimate.bag.model.BagConfirmRequest;
import com.medimate.bag.model.BagConfirmResult;
import com.medimate.bag.model.BagItem;
import com.medimate.bag.model.BagItemPatch;
import com.medimate.bag.model.BagJob;
import com.medimate.bag.model.BagJobStatus;
import com.medimate.drug.model.DrugSection;
import com.medimate.drug.model.MedicationInfo;
import com.medimate.pill.PillRepository;
import com.medimate.pill.model.JobAccepted;
import com.medimate.plan.model.CurrentMedication;
import com.medimate.plan.model.PlanDraft;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * 약봉투 인식·약 상세 API 계약 테스트. 응답 본문(src/test/resources/fixtures/bag_*, medication_*)은
 * 백엔드의 RecognitionApiTest 환경(가짜 추론 서버)에서 실제로 받은 것이다. 백엔드 응답 형식이 바뀌면 이 테스트가 먼저 깨진다.
 * 복약 일정은 서버에 저장되지 않는다: 확정은 일정 초안만 주고, 앱이 Firestore에 저장한다.
 */
public class BagApiContractTest {

    private MockWebServer server;
    private BagRepository bags;
    private PillRepository pills;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        MedimateApi api = ApiClient.create(server.url("/").toString(), false);
        bags = new BagRepository(api);
        pills = new PillRepository(api);
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    // ── 약봉투 ─────────────────────────────────────────────────

    @Test
    public void recognitionUploadsBagPhotoThenRequestsWithFileId() throws Exception {
        server.enqueue(json(201, "upload_201.json"));
        server.enqueue(json(202, "bag_accepted_202.json"));

        JobAccepted accepted = bags.requestRecognition(new byte[]{1, 2, 3});

        assertTrue(accepted.jobId.startsWith("rcg_"));
        assertEquals(2000, accepted.pollAfterMs);
        assertNotNull(accepted.ttsMessage);

        RecordedRequest upload = server.takeRequest();
        assertEquals("/files/images", upload.getPath());
        String multipart = upload.getBody().readUtf8();
        assertTrue(multipart.contains("filename=\"bag.jpg\""));
        assertTrue(multipart.contains("MEDICATION_BAG"));

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/recognition/medication-bags", req.getPath());
        JsonObject body = JsonParser.parseString(req.getBody().readUtf8()).getAsJsonObject();
        assertEquals("f_01M3HH76K0XHTK6QSKF5S2BEWK", body.get("fileId").getAsString());
        assertFalse("값이 없는 항목은 보내지 않는다", body.has("ocrRawText"));
    }

    @Test
    public void jobResultMapsToListAndDetailScreens() throws Exception {
        server.enqueue(json(200, "bag_job_partial_200.json"));

        BagJob job = bags.getJob("rcg_01M6RM82VRYVMTMP43ZDZGQK5D");

        assertEquals("/recognition/jobs/rcg_01M6RM82VRYVMTMP43ZDZGQK5D", server.takeRequest().getPath());
        assertEquals(BagJobStatus.PARTIAL, job.status);
        assertTrue(BagRepository.isFinished(job));
        assertEquals(1, job.reviewRequiredCount);
        assertEquals("행복약국", job.source.pharmacyName);
        assertTrue(job.summaryTtsScript.startsWith("약 두 가지를 찾았습니다"));
        assertEquals(2, job.visibleItems().size());

        // 목록 한 줄: 이름 + 요약
        BagItem tylenol = job.item("i1");
        assertEquals("타이레놀정500밀리그람(아세트아미노펜)", tylenol.displayName());
        assertTrue(tylenol.displayText.startsWith("1회 1정"));
        assertFalse(tylenol.needsReview);
        // 상세 화면 카드: 약 설명·주의사항·복용방법·보관방법 순서, 내용 있음
        List<String> keys = new ArrayList<>();
        for (DrugSection s : tylenol.sectionsOrEmpty()) {
            keys.add(s.key);
        }
        assertEquals(List.of(DrugSection.DESCRIPTION, DrugSection.CAUTION, DrugSection.USE_METHOD,
                DrugSection.STORAGE, DrugSection.SIDE_EFFECT), keys);
        assertTrue(tylenol.sections.get(0).hasText());
        assertEquals("약 설명", tylenol.sections.get(0).title);

        // 식약처와 연결되지 않은 약: 봉투의 이름을 보여 주고, 카드는 안내 문장만
        BagItem unmatched = job.item("i2");
        assertNull(unmatched.itemName);
        assertEquals("뮤코스타", unmatched.displayName());
        assertTrue(unmatched.needsReview);
        assertEquals(2, unmatched.reviewReasons.size());
        assertEquals("2001", unmatched.candidatesOrEmpty().get(0).itemSeq);
        assertFalse(unmatched.sections.get(0).hasText());
        assertTrue(unmatched.sections.get(0).ttsText.contains("식약처 자료와 연결되지 않은 약"));
    }

    @Test
    public void patchSendsOnlyChangedFields() throws Exception {
        server.enqueue(json(200, "bag_patch_item_200.json"));

        BagItem item = bags.patchItem("rcg_x", "i2", BagItemPatch.chooseItem("2001"));

        RecordedRequest req = server.takeRequest();
        assertEquals("PATCH", req.getMethod());
        assertEquals("/recognition/jobs/rcg_x/items/i2", req.getPath());
        assertEquals("{\"itemSeq\":\"2001\"}", req.getBody().readUtf8());
        assertEquals("뮤코스타정(레바미피드)", item.displayName());
        assertFalse(item.needsReview);
    }

    @Test
    public void confirmReturnsDraftsForAppToSave() throws Exception {
        server.enqueue(json(201, "bag_confirm_201.json"));

        BagConfirmResult result = bags.confirm("rcg_x", BagConfirmRequest.acceptAll(null));

        RecordedRequest req = server.takeRequest();
        assertEquals("/recognition/jobs/rcg_x/confirm", req.getPath());
        assertEquals("{\"acceptAll\":true}", req.getBody().readUtf8());
        assertEquals("CONFIRMED", result.status);
        // 저장할 일정 초안: 약마다 하나
        assertEquals(2, result.plansOrEmpty().size());
        PlanDraft first = result.plans.get(0);
        assertEquals("1001", first.itemSeq);
        assertEquals(Double.valueOf(1.0), first.dosageAmount);
        assertEquals("TABLET", first.dosageUnit);
        assertEquals(3, first.timeSlotsOrEmpty().size());
        assertEquals("08:00", first.timeSlots.get(0).time);
        assertEquals("2026-11-05", first.startDate);
        assertEquals("2026-11-09", first.endDate);
        assertTrue(first.notificationEnabled);
        assertNotNull(first.memo);
        // 화면용 요약은 같은 순서이고 itemId로 결과 목록과 연결된다
        assertEquals("i1", result.summariesOrEmpty().get(0).itemId);
        assertEquals(14, result.summaries.get(0).generatedDoseCount);
        assertTrue(result.skippedItemsOrEmpty().isEmpty());
        assertEquals("알람으로 설정할까요?", result.alarmQuestion);
    }

    @Test
    public void confirmSendsCurrentMedicationsAndReportsSkipped() throws Exception {
        server.enqueue(json(201, "bag_confirm_skipped_201.json"));

        BagConfirmResult result = bags.confirm("rcg_x", BagConfirmRequest.acceptAll(
                List.of(CurrentMedication.of("1001"), new CurrentMedication(null, "혈압약"))));

        JsonObject sent = JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
        assertEquals("1001", sent.getAsJsonArray("currentMedications").get(0).getAsJsonObject().get("itemSeq").getAsString());
        assertEquals("혈압약", sent.getAsJsonArray("currentMedications").get(1).getAsJsonObject().get("name").getAsString());
        assertEquals(1, result.plansOrEmpty().size());
        assertEquals("i1", result.skippedItemsOrEmpty().get(0).itemId);
        assertEquals("이미 복용 중인 약입니다", result.skippedItems.get(0).reason);
    }

    @Test
    public void confirmConflictWhenAlreadyTaking() throws Exception {
        server.enqueue(json(409, "bag_confirm_duplicate_409.json"));
        BagConfirmRequest req = new BagConfirmRequest();
        req.currentMedications = List.of(CurrentMedication.of("2001"));
        try {
            bags.confirm("rcg_x", req);
            fail();
        } catch (ApiException e) {
            assertEquals(409, e.httpStatus());
            assertEquals("MM-RCG-4094", e.error().code);
            assertEquals("i2", e.error().details.get(0).field);
        }
    }

    @Test
    public void pollerStopsWhenBagJobFinishes() {
        List<BagJob> done = new ArrayList<>();
        BagJob processing = new BagJob();
        processing.status = BagJobStatus.PROCESSING;
        BagJob finished = new BagJob();
        finished.status = BagJobStatus.COMPLETED;
        int[] calls = {0};
        new JobPoller<BagJob>(id -> ++calls[0] < 3 ? processing : finished, BagRepository::isFinished,
                Runnable::run, (task, delay) -> task.run(), 60_000)
                .start("rcg_x", 2000, new JobPoller.Listener<BagJob>() {
                    @Override public void onProgress(BagJob job) { }
                    @Override public void onDone(BagJob job) { done.add(job); }
                    @Override public void onError(ApiException error) { fail(); }
                    @Override public void onTimeout() { fail(); }
                });
        assertEquals(3, calls[0]);
        assertEquals(BagJobStatus.COMPLETED, done.get(0).status);
    }

    // ── 약 상세 (알약 선택 후) ──────────────────────────────────

    @Test
    public void medicationDetailHasSectionsEvenWithoutInfo() throws Exception {
        server.enqueue(json(200, "medication_200.json"));

        MedicationInfo info = pills.getMedication("2001");

        assertEquals("/medications/2001", server.takeRequest().getPath());
        assertEquals("뮤코스타정(레바미피드)", info.itemName);
        assertEquals("NOT_IN_MFDS", info.infoStatus);
        assertNull(info.info);
        assertEquals(4, info.sectionsOrEmpty().size());
        DrugSection storage = info.section(DrugSection.STORAGE);
        assertEquals("보관방법", storage.title);
        assertFalse(storage.hasText());
        assertTrue(storage.ttsText.startsWith("보관방법입니다."));
        assertNull(info.section(DrugSection.INTERACTION));
    }

    private static MockResponse json(int code, String fixture) throws IOException {
        try (InputStream in = BagApiContractTest.class.getResourceAsStream("/fixtures/" + fixture)) {
            if (in == null) {
                throw new IOException("fixture 없음: " + fixture);
            }
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json; charset=utf-8")
                    .setBody(body);
        }
    }
}
