package com.medimate.pill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

import com.medimate.net.ApiClient;
import com.medimate.net.ApiException;
import com.medimate.pill.model.JobAccepted;
import com.medimate.pill.model.PillHints;
import com.medimate.pill.model.PillIdentificationJob;
import com.medimate.pill.model.PillJobStatus;
import com.medimate.pill.model.SelectResult;

/**
 * 실행 중인 백엔드 + 추론 서버(데모 DB)에 앱의 네트워크 코드로 실제 요청을 보낸다.
 * 환경변수 MEDIMATE_LIVE_URL(예: http://localhost:8080/)이 있을 때만 실행된다.
 */
public class LiveBackendTest {

    private static final String BASE_URL = System.getenv("MEDIMATE_LIVE_URL");
    private static final Path PHOTOS = Paths.get(System.getenv().getOrDefault("MEDIMATE_DEMO_PHOTOS",
            "../pill-classifier/data/demo/photos"));

    @Test
    public void identifyAndSelectDemoPill() throws Exception {
        assumeTrue("MEDIMATE_LIVE_URL이 없어서 건너뜀", BASE_URL != null);
        PillRepository repo = new PillRepository(ApiClient.create(BASE_URL, false));

        byte[] photo = Files.readAllBytes(PHOTOS.resolve("red_white_capsule.jpg"));
        JobAccepted accepted = repo.requestIdentification(photo, photo, new PillHints(null, null, null));
        PillIdentificationJob job = waitForResult(repo, accepted);

        assertEquals(PillJobStatus.COMPLETED, job.status);
        assertEquals("DEMO0003", job.topMatch.itemSeq);
        SelectResult selected = repo.select(job.jobId, job.topMatch.itemSeq);
        assertEquals("CAPSULE", selected.draftPlan.dosageUnit);
    }

    @Test
    public void blurryPhotoIsRejectedWithTtsMessage() throws Exception {
        assumeTrue(BASE_URL != null);
        PillRepository repo = new PillRepository(ApiClient.create(BASE_URL, false));
        try {
            repo.uploadPillPhoto(Files.readAllBytes(PHOTOS.resolve("blurry.jpg")), "blurry.jpg");
            fail("흐린 사진은 거절되어야 한다");
        } catch (ApiException e) {
            assertEquals("MM-FIL-4220", e.error().code);
            assertTrue(e.isRetakeNeeded());
        }
    }

    private static PillIdentificationJob waitForResult(PillRepository repo, JobAccepted accepted)
            throws Exception {
        for (int i = 0; i < 40; i++) {
            Thread.sleep(Math.max(250, accepted.pollAfterMs / 3));
            PillIdentificationJob job = repo.getJob(accepted.jobId);
            if (job.status.isTerminal()) {
                return job;
            }
        }
        throw new AssertionError("식별이 끝나지 않았습니다");
    }
}
