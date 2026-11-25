package com.medimate.pill;

import java.util.ArrayList;
import java.util.List;

import com.medimate.drug.model.MedicationInfo;
import com.medimate.net.ApiClient;
import com.medimate.net.ApiException;
import com.medimate.net.MedimateApi;
import com.medimate.pill.model.ColorClass;
import com.medimate.pill.model.DrugShape;
import com.medimate.pill.model.IdentificationRequest;
import com.medimate.pill.model.JobAccepted;
import com.medimate.pill.model.PillHints;
import com.medimate.pill.model.PillIdentificationJob;
import com.medimate.pill.model.SearchResult;
import com.medimate.pill.model.SelectRequest;
import com.medimate.pill.model.SelectResult;
import com.medimate.pill.model.UploadedFile;
import com.medimate.plan.model.CurrentMedication;

/** 알약 식별 API 호출 모음. 모든 메서드는 네트워크를 쓰므로 백그라운드 스레드에서 호출한다. */
public class PillRepository {

    private final MedimateApi api;

    public PillRepository(MedimateApi api) {
        this.api = api;
    }

    public String uploadPillPhoto(byte[] jpeg, String filename) throws ApiException {
        UploadedFile f = ApiClient.execute(api.uploadImage(ApiClient.jpegPart("file", filename, jpeg),
                ApiClient.textPart("PILL")));
        return f.fileId;
    }

    /** 앞면(필수)·뒷면(선택)을 올리고 식별을 요청한다. */
    public JobAccepted requestIdentification(byte[] frontJpeg, byte[] backJpeg, PillHints hints)
            throws ApiException {
        return requestIdentification(frontJpeg, backJpeg, hints, null);
    }

    /**
     * currentMedications: Firestore에 저장된 복용 중인 약. 주면 그 약인 후보에 isInMyMedications가 켜진다
     * (소분해 둔 약을 확인하는 경우).
     */
    public JobAccepted requestIdentification(byte[] frontJpeg, byte[] backJpeg, PillHints hints,
                                             List<CurrentMedication> currentMedications) throws ApiException {
        String front = uploadPillPhoto(frontJpeg, "front.jpg");
        String back = backJpeg == null ? null : uploadPillPhoto(backJpeg, "back.jpg");
        return ApiClient.execute(api.requestIdentification(
                new IdentificationRequest(front, back, hints, currentMedications)));
    }

    public PillIdentificationJob getJob(String jobId) throws ApiException {
        return ApiClient.execute(api.getIdentification(jobId));
    }

    /** JobPoller의 종료 조건: 더 조회할 필요가 없는 상태인지. */
    public static boolean isFinished(PillIdentificationJob job) {
        return job != null && job.status != null && job.status.isTerminal();
    }

    /**
     * 약 상세 화면용: 품목 정보 + 약 설명·주의사항·복용방법·보관방법(sections).
     * 후보를 고른 뒤(select) 그 itemSeq로 부른다.
     */
    public MedicationInfo getMedication(String itemSeq) throws ApiException {
        return ApiClient.execute(api.getMedication(itemSeq));
    }

    /**
     * 사용자가 고른 약을 확정한다. 약 정보와 일정 초안(draftPlan)을 돌려준다.
     * 내 약에 추가하려면 draftPlan으로 앱이 Firestore에 직접 저장한다(서버는 저장하지 않는다).
     */
    public SelectResult select(String jobId, String itemSeq) throws ApiException {
        return ApiClient.execute(api.select(jobId, new SelectRequest(itemSeq)));
    }

    public SearchResult search(DrugShape shape, List<ColorClass> colors, String printText, String itemName,
                               String cursor, int size) throws ApiException {
        List<String> colorNames = null;
        if (colors != null && !colors.isEmpty()) {
            colorNames = new ArrayList<>();
            for (ColorClass c : colors) {
                colorNames.add(c.name());
            }
        }
        return ApiClient.execute(api.search(shape == null ? null : shape.name(), colorNames,
                blankToNull(printText), blankToNull(itemName), cursor, size));
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
