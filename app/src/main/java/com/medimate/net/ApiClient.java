package com.medimate.net;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.medimate.BuildConfig;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/** Retrofit 클라이언트와 동기 호출 도우미. 호출은 반드시 백그라운드 스레드에서 한다. */
public final class ApiClient {

    public static final Gson GSON = new Gson();
    private static final MediaType JPEG = MediaType.get("image/jpeg");
    private static volatile MedimateApi instance;

    private ApiClient() {
    }

    /** BuildConfig.API_BASE_URL(app/build.gradle.kts)로 만든 기본 클라이언트. */
    public static MedimateApi get() {
        if (instance == null) {
            synchronized (ApiClient.class) {
                if (instance == null) {
                    instance = create(BuildConfig.API_BASE_URL, BuildConfig.DEBUG);
                }
            }
        }
        return instance;
    }

    public static MedimateApi create(String baseUrl, boolean logging) {
        OkHttpClient.Builder http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS); // 느린 망에서 사진 업로드
        // TODO(인증): 로그인 연동 시 Authorization: Bearer 헤더를 붙이는 인터셉터 추가
        if (logging) {
            // BODY로 두면 사진 바이트가 로그에 찍히므로 BASIC만 쓴다
            HttpLoggingInterceptor log = new HttpLoggingInterceptor();
            log.setLevel(HttpLoggingInterceptor.Level.BASIC);
            http.addInterceptor(log);
        }
        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(http.build())
                .addConverterFactory(GsonConverterFactory.create(GSON))
                .build()
                .create(MedimateApi.class);
    }

    /** 성공이면 data를, 아니면 서버의 에러 본문을 담은 ApiException을 던진다. */
    public static <T> T execute(Call<ApiResponse<T>> call) throws ApiException {
        Response<ApiResponse<T>> response;
        try {
            response = call.execute();
        } catch (IOException e) {
            throw ApiException.network(e);
        }
        ApiResponse<T> body = response.body();
        if (response.isSuccessful() && body != null && body.success) {
            return body.data;
        }
        throw ApiException.fromErrorBody(response.code(), response.errorBody());
    }

    public static MultipartBody.Part jpegPart(String name, String filename, byte[] jpeg) {
        return MultipartBody.Part.createFormData(name, filename, RequestBody.create(jpeg, JPEG));
    }

    public static RequestBody textPart(String value) {
        return RequestBody.create(value, MediaType.get("text/plain; charset=utf-8"));
    }
}
