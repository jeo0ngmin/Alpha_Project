package com.medimate.bag;

import com.medimate.bag.model.BagConfirmRequest;
import com.medimate.bag.model.BagConfirmResult;
import com.medimate.bag.model.BagItem;
import com.medimate.bag.model.BagItemPatch;
import com.medimate.bag.model.BagJob;
import com.medimate.bag.model.BagRecognitionRequest;
import com.medimate.net.ApiClient;
import com.medimate.net.ApiException;
import com.medimate.net.MedimateApi;
import com.medimate.pill.model.JobAccepted;
import com.medimate.pill.model.UploadedFile;

/**
 * 약봉투 인식 API 호출 모음. 모든 메서드는 네트워크를 쓰므로 백그라운드 스레드에서 호출한다.
 *
 * <pre>
 * 1. requestRecognition(jpeg)        사진 업로드 + 인식 요청 → JobAccepted(jobId, pollAfterMs)
 * 2. JobPoller로 getJob 반복          new JobPoller&lt;&gt;(repo::getJob, BagRepository::isFinished, ...)
 *    → status COMPLETED / PARTIAL     결과 목록: job.visibleItems() (이름 displayName(), 요약 displayText)
 *    → status FAILED                  job.error.ttsMessage 안내, error.retryable이면 다시 찍기
 * 3. 상세 화면                         item.sections를 그대로 버튼으로 (추가 조회 없음)
 * 4. (선택) 확인이 필요한 약           item.needsReview → patchItem(...)
 * 5. 일정 초안 받기                    confirm(jobId, BagConfirmRequest.acceptAll(복용 중인 약))
 *    → result.plans를 Firestore에 저장, alarmQuestion 답에 따라 알람
 * </pre>
 * 서버는 복약 일정을 저장하지 않는다. 이미 먹는 약인지는 앱이 보낸 currentMedications로만 판단한다.
 */
public class BagRepository {

    private final MedimateApi api;

    public BagRepository(MedimateApi api) {
        this.api = api;
    }

    public String uploadBagPhoto(byte[] jpeg) throws ApiException {
        UploadedFile f = ApiClient.execute(api.uploadImage(ApiClient.jpegPart("file", "bag.jpg", jpeg),
                ApiClient.textPart("MEDICATION_BAG")));
        return f.fileId;
    }

    /** 사진을 올리고 인식을 요청한다. 사진은 ImageCompressor로 줄인 JPEG를 넘긴다. */
    public JobAccepted requestRecognition(byte[] jpeg) throws ApiException {
        String fileId = uploadBagPhoto(jpeg);
        return ApiClient.execute(api.requestBagRecognition(new BagRecognitionRequest(fileId, null, null)));
    }

    public BagJob getJob(String jobId) throws ApiException {
        return ApiClient.execute(api.getBagJob(jobId));
    }

    /** JobPoller의 종료 조건: 더 조회할 필요가 없는 상태인지. */
    public static boolean isFinished(BagJob job) {
        return job != null && job.isFinished();
    }

    /** 확정 전에 약 하나를 고친다(후보 선택, 복용량 수정, 빼기). 고친 약을 돌려준다. */
    public BagItem patchItem(String jobId, String itemId, BagItemPatch patch) throws ApiException {
        return ApiClient.execute(api.patchBagItem(jobId, itemId, patch));
    }

    /**
     * 읽은 약들을 확정하고 약마다 일정 초안을 받는다. 서버에는 저장되지 않으므로 result.plans를 앱이 저장한다.
     * 같은 jobId로 다시 부르면 처음 결과를 그대로 돌려준다.
     */
    public BagConfirmResult confirm(String jobId, BagConfirmRequest request) throws ApiException {
        return ApiClient.execute(api.confirmBag(jobId, request == null ? new BagConfirmRequest() : request));
    }
}
