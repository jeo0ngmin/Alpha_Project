package com.medimate.debugtools;

import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.medimate.BuildConfig;
import com.medimate.net.ApiResponse;

import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.Path;

/**
 * [시험용·디버그 전용] 약봉투 인식 API (실제 화면용은 app/src/main의 BagRepository). 화면 담당 팀원의 실제 구현과 합칠 때는 이 폴더(src/debug)를 빼면 된다.
 * 응답은 JsonObject로 받아 모델 클래스를 만들지 않는다.
 */
public interface BagTestApi {

    @POST("recognition/medication-bags")
    Call<ApiResponse<JsonObject>> recognize(@Body JsonObject body);

    @GET("recognition/jobs/{jobId}")
    Call<ApiResponse<JsonObject>> job(@Path("jobId") String jobId);

    @PATCH("recognition/jobs/{jobId}/items/{itemId}")
    Call<ApiResponse<JsonObject>> patchItem(@Path("jobId") String jobId, @Path("itemId") String itemId, @Body JsonObject body);

    @POST("recognition/jobs/{jobId}/confirm")
    Call<ApiResponse<JsonObject>> confirm(@Path("jobId") String jobId, @Body JsonObject body);

    static BagTestApi create() {
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        return new Retrofit.Builder()
                .baseUrl(BuildConfig.API_BASE_URL)
                .client(http)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(BagTestApi.class);
    }
}
