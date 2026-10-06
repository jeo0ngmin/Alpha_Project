package com.medimate.recommend;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.medimate.BuildConfig;
import com.medimate.gpt.OpenAiClient;
import com.medimate.gpt.model.ChatRequest;
import com.medimate.gpt.model.ChatResponse;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 사용자가 채팅으로 말한 증상을 규칙 표의 고민 이름으로 분류한다 (README 8-2).
 * GPT는 여기까지만 쓴다. 추천 성분·설명은 규칙 표(RecommendEngine)가 정한다.
 */
public class ConcernClassifier {

    private static final String TAG = "ConcernClassifier";
    private static final String MODEL = "gpt-4o";

    static final String MSG_NO_KEY = "설정 오류예요. OpenAI 키가 없어요.";
    static final String MSG_FAILED = "말씀을 이해하지 못했어요. 다시 말씀해 주세요.";
    static final String MSG_NETWORK = "인터넷 연결을 확인해 주세요.";

    public interface ClassifyCallback {
        /** 해당하는 고민 이름들 (규칙 표에 있는 것만). 없으면 빈 목록 */
        void onResult(List<String> concerns);

        void onError(String message);
    }

    private final Gson gson = new Gson();

    public void classify(String userText, List<String> concerns, ClassifyCallback callback) {
        if (BuildConfig.OPENAI_API_KEY.isEmpty()) {
            Log.e(TAG, "secrets.properties에 OPENAI_API_KEY가 없다");
            callback.onError(MSG_NO_KEY);
            return;
        }
        String prompt = "사용자가 말한 건강 고민이나 증상을 아래 목록의 항목으로 분류한다.\n"
                + "목록: " + concerns + "\n"
                + "규칙:\n"
                + "- 목록에 있는 이름만, 글자 그대로 쓴다. 목록에 없는 항목은 만들지 않는다.\n"
                + "- 해당하는 항목이 여러 개면 모두 넣는다. 하나도 없으면 빈 배열로 둔다.\n"
                + "- 성분, 제품, 건강 정보, 조언은 쓰지 않는다.\n"
                + "다음 JSON만 출력한다: {\"concerns\":[\"...\"]}";

        OpenAiClient.get().chat("Bearer " + BuildConfig.OPENAI_API_KEY, new ChatRequest(MODEL, prompt, userText))
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
                        List<String> result = parse(content, concerns);
                        if (result == null) {
                            callback.onError(MSG_FAILED);
                            return;
                        }
                        callback.onResult(result);
                    }

                    @Override
                    public void onFailure(@NonNull Call<ChatResponse> call, @NonNull Throwable t) {
                        Log.e(TAG, "OpenAI 호출 실패", t);
                        callback.onError(MSG_NETWORK);
                    }
                });
    }

    /** 응답에서 고민 이름을 꺼내되, 규칙 표에 없는 이름은 버린다. 파싱 실패면 null */
    List<String> parse(String content, List<String> allowed) {
        String json = content.trim();
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```(json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        try {
            ClassifyResult result = gson.fromJson(json, ClassifyResult.class);
            List<String> filtered = new ArrayList<>();
            if (result != null && result.concerns != null) {
                for (String concern : result.concerns) {
                    if (allowed.contains(concern) && !filtered.contains(concern)) {
                        filtered.add(concern);
                    }
                }
            }
            return filtered;
        } catch (JsonSyntaxException e) {
            Log.e(TAG, "분류 응답 파싱 실패", e);
            return null;
        }
    }

    static class ClassifyResult {
        List<String> concerns;
    }
}
