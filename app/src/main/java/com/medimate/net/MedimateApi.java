package com.medimate.net;

import java.util.List;

import com.medimate.bag.model.BagConfirmRequest;
import com.medimate.bag.model.BagConfirmResult;
import com.medimate.bag.model.BagItem;
import com.medimate.bag.model.BagItemPatch;
import com.medimate.bag.model.BagJob;
import com.medimate.bag.model.BagRecognitionRequest;
import com.medimate.drug.model.MedicationInfo;
import com.medimate.pill.model.IdentificationRequest;
import com.medimate.pill.model.JobAccepted;
import com.medimate.pill.model.PillIdentificationJob;
import com.medimate.pill.model.SearchResult;
import com.medimate.pill.model.SelectRequest;
import com.medimate.pill.model.SelectResult;
import com.medimate.pill.model.UploadedFile;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * MediMate 백엔드 API: 파일 업로드, 알약 식별, 약 상세, 약봉투 인식. 경로는 baseUrl 기준 상대 경로다.
 * 복약 일정은 서버가 아니라 앱(Firestore)에 저장한다. 화면에서는 직접 부르지 말고 PillRepository, BagRepository를 쓴다.
 */
public interface MedimateApi {

    @Multipart
    @POST("files/images")
    Call<ApiResponse<UploadedFile>> uploadImage(@Part MultipartBody.Part file, @Part("purpose") RequestBody purpose);

    @POST("pill-identification")
    Call<ApiResponse<JobAccepted>> requestIdentification(@Body IdentificationRequest request);

    @GET("pill-identification/{jobId}")
    Call<ApiResponse<PillIdentificationJob>> getIdentification(@Path("jobId") String jobId);

    @POST("pill-identification/{jobId}/select")
    Call<ApiResponse<SelectResult>> select(@Path("jobId") String jobId, @Body SelectRequest request);

    @GET("pill-identification/search")
    Call<ApiResponse<SearchResult>> search(@Query("drugShape") String drugShape,
                                           @Query("colorClass") List<String> colorClass,
                                           @Query("printText") String printText,
                                           @Query("itemName") String itemName,
                                           @Query("cursor") String cursor,
                                           @Query("size") Integer size);

    // ── 약 상세 ─────────────────────────────────────────────────

    /** 품목 정보 + e약은요 설명 + 상세 화면 버튼(sections). 알약 선택 후 상세 화면에서 쓴다. */
    @GET("medications/{itemSeq}")
    Call<ApiResponse<MedicationInfo>> getMedication(@Path("itemSeq") String itemSeq);

    // ── 약봉투 인식 ──────────────────────────────────────────────

    @POST("recognition/medication-bags")
    Call<ApiResponse<JobAccepted>> requestBagRecognition(@Body BagRecognitionRequest request);

    @GET("recognition/jobs/{jobId}")
    Call<ApiResponse<BagJob>> getBagJob(@Path("jobId") String jobId);

    @PATCH("recognition/jobs/{jobId}/items/{itemId}")
    Call<ApiResponse<BagItem>> patchBagItem(@Path("jobId") String jobId, @Path("itemId") String itemId,
                                            @Body BagItemPatch patch);

    @POST("recognition/jobs/{jobId}/confirm")
    Call<ApiResponse<BagConfirmResult>> confirmBag(@Path("jobId") String jobId, @Body BagConfirmRequest request);
}
