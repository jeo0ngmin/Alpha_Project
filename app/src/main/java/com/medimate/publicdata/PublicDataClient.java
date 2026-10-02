package com.medimate.publicdata;

import android.util.Log;

import com.medimate.BuildConfig;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/** 식약처 공공데이터 Retrofit 싱글턴 (README 5-4). */
public final class PublicDataClient {

    private static final String TAG = "PublicData";
    private static final String BASE_URL = "https://apis.data.go.kr/1471000/";
    private static final long TIMEOUT_SECONDS = 10;

    private static volatile Retrofit retrofit;

    private PublicDataClient() {
    }

    public static Retrofit get() {
        if (retrofit == null) {
            synchronized (PublicDataClient.class) {
                if (retrofit == null) {
                    retrofit = new Retrofit.Builder()
                            .baseUrl(BASE_URL)
                            .client(buildHttpClient())
                            .addConverterFactory(GsonConverterFactory.create())
                            .build();
                }
            }
        }
        return retrofit;
    }

    private static OkHttpClient buildHttpClient() {
        // 요청 URL에 serviceKey가 찍히므로 로그에서 값을 가린다. 릴리스에서는 로그를 끈다 (README 10장).
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor(message ->
                Log.d(TAG, redactServiceKey(message)));
        logging.setLevel(BuildConfig.DEBUG ? HttpLoggingInterceptor.Level.BASIC
                                           : HttpLoggingInterceptor.Level.NONE);

        return new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build();
    }

    static String redactServiceKey(String message) {
        return message.replaceAll("(serviceKey=)[^&\\s]+", "$1****");
    }
}
