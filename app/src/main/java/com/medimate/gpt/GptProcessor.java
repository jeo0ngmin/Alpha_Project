package com.medimate.gpt;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.medimate.BuildConfig;
import com.medimate.gpt.model.ChatRequest;
import com.medimate.gpt.model.ChatResponse;
import com.medimate.gpt.model.GptDrugList;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * OCR 원문에서 약 이름·용법·보관법만 구조화한다 (README 5-5).
 * 효능·주의사항·상호작용은 GPT가 만들지 않는다. 그 항목은 e약은요에서 채운다.
 */
public class GptProcessor {

    private static final String TAG = "GptProcessor";
    private static final String MODEL = "gpt-4o";

    static final String MSG_NO_KEY = "설정 오류예요. OpenAI 키가 없어요.";
    static final String MSG_FAILED = "약 이름을 정리하지 못했어요. 다시 시도해 주세요.";
    static final String MSG_NETWORK = "인터넷 연결을 확인해 주세요.";

    private static final String SYSTEM_PROMPT =
            "너는 약봉투를 OCR한 원문에서 약 이름과 용법을 구조화하는 도우미다. 원문에 적힌 내용만 쓴다.\n"
            + "효능, 주의사항, 설명, 생김새는 절대 만들지 않는다.\n"
            + "다음 JSON만 출력한다:\n"
            + "{\"drugs\":[{\"name\":\"\",\"dosage\":\"\",\"storage\":\"\"}],\"common_instructions\":\"\"}\n"
            + "규칙:\n"
            + "- name: 원문의 약 이름 표기를 용량까지 그대로 적는다 (예: 타이레놀정500밀리그램). OCR 오타는 고치지 않는다.\n"
            + "- 약국 이름, 환자 이름, 병원 이름, '약품명' 같은 표 제목, 복용 안내 문장은 약이 아니므로 넣지 않는다.\n"
            + "- dosage: 그 약의 투약량·횟수·일수·복용 시점을 원문 표현으로 한 문장에 적는다. 없으면 빈 문자열.\n"
            + "- storage: 원문에 보관법이 있을 때만 적는다. 없으면 빈 문자열.\n"
            + "- common_instructions: 모든 약에 공통인 복용 안내가 있으면 적는다. 없으면 빈 문자열.\n"
            + "- 약 이름을 하나도 찾지 못하면 drugs를 빈 배열로 둔다.";

    private final Gson gson = new Gson();

    public void process(String ocrText, GptCallback callback) {
        if (BuildConfig.OPENAI_API_KEY.isEmpty()) {
            Log.e(TAG, "secrets.properties에 OPENAI_API_KEY가 없다");
            callback.onError(MSG_NO_KEY);
            return;
        }

        ChatRequest request = new ChatRequest(MODEL, SYSTEM_PROMPT, ocrText);
        OpenAiClient.get().chat("Bearer " + BuildConfig.OPENAI_API_KEY, request)
                .enqueue(new Callback<ChatResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ChatResponse> call,
                                           @NonNull Response<ChatResponse> response) {
                        String content = response.body() == null ? null : response.body().getContent();
                        if (!response.isSuccessful() || content == null) {
                            Log.e(TAG, "OpenAI HTTP " + response.code());
                            callback.onError(MSG_FAILED);
                            return;
                        }
                        GptDrugList result = parse(content);
                        if (result == null) {
                            callback.onError(MSG_FAILED);
                            return;
                        }
                        Log.i(TAG, "GPT 정리: 약 " + result.getDrugs().size() + "개");
                        callback.onSuccess(result);
                    }

                    @Override
                    public void onFailure(@NonNull Call<ChatResponse> call, @NonNull Throwable t) {
                        Log.e(TAG, "OpenAI 호출 실패", t);
                        callback.onError(MSG_NETWORK);
                    }
                });
    }

    /** JSON 모드여도 가끔 ```json 코드블록으로 감싸 오므로 벗기고 파싱한다. 실패하면 null */
    GptDrugList parse(String content) {
        String json = content.trim();
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```(json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        try {
            return gson.fromJson(json, GptDrugList.class);
        } catch (JsonSyntaxException e) {
            Log.e(TAG, "GPT 응답 파싱 실패", e);
            return null;
        }
    }
}
