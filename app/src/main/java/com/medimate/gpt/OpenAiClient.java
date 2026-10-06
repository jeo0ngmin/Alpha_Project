package com.medimate.gpt;

import android.util.Log;

import com.medimate.BuildConfig;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/** OpenAI Retrofit 싱글턴. 요청 본문(OCR 원문)은 환자 정보라서 로그에 남기지 않는다 (README 13장). */
public final class OpenAiClient {

    private static final String TAG = "OpenAi";
    private static final String BASE_URL = "https://api.openai.com/";
    private static final long TIMEOUT_SECONDS = 30;

    private static volatile OpenAiApi api;

    private OpenAiClient() {
    }

    public static OpenAiApi get() {
        if (api == null) {
            synchronized (OpenAiClient.class) {
                if (api == null) {
                    api = new Retrofit.Builder()
                            .baseUrl(BASE_URL)
                            .client(buildHttpClient())
                            .addConverterFactory(GsonConverterFactory.create())
                            .build()
                            .create(OpenAiApi.class);
                }
            }
        }
        return api;
    }

    private static OkHttpClient buildHttpClient() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor(message -> Log.d(TAG, message));
        logging.redactHeader("Authorization");
        // BASIC은 본문을 찍지 않는다. 릴리스에서는 끈다.
        logging.setLevel(BuildConfig.DEBUG ? HttpLoggingInterceptor.Level.BASIC
                                           : HttpLoggingInterceptor.Level.NONE);

        return new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build();
    }
}
