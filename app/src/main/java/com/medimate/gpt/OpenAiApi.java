package com.medimate.gpt;

import com.medimate.gpt.model.ChatRequest;
import com.medimate.gpt.model.ChatResponse;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;

public interface OpenAiApi {

    @POST("v1/chat/completions")
    Call<ChatResponse> chat(
            @Header("Authorization") String bearer,   // "Bearer " + BuildConfig.OPENAI_API_KEY
            @Body ChatRequest body
    );
}
