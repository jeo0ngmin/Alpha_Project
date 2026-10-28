package com.medimate.recommend;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.medimate.BuildConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SymptomAnalyzer의 Claude 구현 (README 8-6).
 * 고민 이름은 JSON 스키마의 enum으로 묶어 규칙 표에 없는 이름이 나오지 않게 한다.
 * 대화 본문은 환자 정보라서 로그에 남기지 않는다.
 */
public class ClaudeSymptomAnalyzer implements SymptomAnalyzer {

    private static final String TAG = "ClaudeSymptom";
    private static final String MODEL = "claude-opus-5-5";

    static final String MSG_NO_KEY = "설정 오류예요. Claude 키가 없어요.";
    static final String MSG_FAILED = "말씀을 이해하지 못했어요. 다시 말씀해 주세요.";
    static final String MSG_NETWORK = "인터넷 연결을 확인해 주세요.";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private AnthropicClient client;

    @Override
    public void analyze(List<Turn> conversation, List<String> concerns, Callback callback) {
        if (BuildConfig.CLAUDE_API_KEY.isEmpty()) {
            Log.e(TAG, "secrets.properties에 CLAUDE_API_KEY가 없다");
            callback.onError(MSG_NO_KEY);
            return;
        }
        List<Turn> turns = new ArrayList<>(conversation);
        List<String> allowed = new ArrayList<>(concerns);
        executor.execute(() -> {
            try {
                SymptomAnalysis result = request(turns, allowed);
                main.post(() -> {
                    if (result == null) {
                        callback.onError(MSG_FAILED);
                    } else {
                        callback.onResult(result);
                    }
                });
            } catch (AnthropicServiceException e) {
                Log.e(TAG, "Claude HTTP " + e.statusCode());
                main.post(() -> callback.onError(MSG_FAILED));
            } catch (AnthropicIoException e) {
                Log.e(TAG, "Claude 연결 실패", e);
                main.post(() -> callback.onError(MSG_NETWORK));
            } catch (RuntimeException e) {
                Log.e(TAG, "Claude 호출 실패: " + e.getClass().getSimpleName());
                main.post(() -> callback.onError(MSG_FAILED));
            }
        });
    }

    private SymptomAnalysis request(List<Turn> turns, List<String> concerns) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(4096L)
                .system(systemPrompt(concerns))
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.LOW)
                        .format(JsonOutputFormat.builder().schema(schema(concerns)).build())
                        .build())
                // 안전 분류기가 거절하면 서버가 다른 모델로 다시 시도한다
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        for (Turn turn : turns) {
            if (turn.fromUser) {
                params.addUserMessage(turn.text);
            } else {
                params.addAssistantMessage(turn.text);
            }
        }

        Message response = client().messages().create(params.build());
        if (response.stopReason().map(StopReason.REFUSAL::equals).orElse(false)) {
            Log.w(TAG, "Claude가 응답을 거절했다");
            return null;
        }
        StringBuilder text = new StringBuilder();
        response.content().forEach(block -> block.text().ifPresent(t -> text.append(t.text())));
        SymptomAnalysis result = parse(text.toString(), concerns);
        if (result == null) {
            Log.e(TAG, "Claude 응답 파싱 실패");
        }
        return result;
    }

    private synchronized AnthropicClient client() {
        if (client == null) {
            client = AnthropicOkHttpClient.builder().apiKey(BuildConfig.CLAUDE_API_KEY).build();
        }
        return client;
    }

    static String systemPrompt(List<String> concerns) {
        return "너는 건강기능식품 추천 앱의 상담 도우미다. 사용자는 주로 고령층과 시각장애인이다.\n"
                + "할 일: 대화에서 사용자의 증상을 파악해 아래 고민 목록의 항목으로 바꾼다. "
                + "추천 성분은 앱이 규칙 표로 정하므로 너는 정하지 않는다.\n"
                + "고민 목록: " + String.join(", ", concerns) + "\n"
                + "규칙:\n"
                + "- concerns에는 목록의 이름만 글자 그대로 넣는다. 해당하는 것이 여러 개면 모두 넣는다.\n"
                + "- 한 항목이라도 분명히 해당하면 needs_more_info는 false로 두고 더 묻지 않는다.\n"
                + "- 증상이 모호해서 어느 항목인지 고를 수 없을 때만 needs_more_info를 true로 두고, "
                + "follow_up_question에 질문 한 개를 쓴다. 질문은 증상(어디가, 어떻게, 언제부터)만 묻는 "
                + "짧은 존댓말로, 소리 내어 읽어도 알아듣기 쉽게 쓴다.\n"
                + "- 건강 고민과 관계없는 말이면 concerns를 비우고 needs_more_info는 false로 둔다.\n"
                + "- 진단, 성분, 제품, 복용법, 건강 조언은 어디에도 쓰지 않는다.\n"
                + "- symptom_summary에는 사용자가 말한 증상을 한 줄로 정리한다. 말하지 않은 내용은 넣지 않는다.\n"
                + "- needs_more_info가 false면 follow_up_question은 빈 문자열로 둔다.";
    }

    /** 고민 이름을 enum으로 묶은 출력 스키마 */
    static JsonOutputFormat.Schema schema(List<String> concerns) {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "string");
        items.put("enum", concerns);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("concerns", map("type", "array", "items", items));
        properties.put("needs_more_info", map("type", "boolean"));
        properties.put("follow_up_question", map("type", "string"));
        properties.put("symptom_summary", map("type", "string"));

        return JsonOutputFormat.Schema.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(properties))
                .putAdditionalProperty("required", JsonValue.from(Arrays.asList(
                        "concerns", "needs_more_info", "follow_up_question", "symptom_summary")))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build();
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    /** 응답 JSON을 읽고, 규칙 표에 없는 고민 이름은 버린다. 파싱 실패면 null */
    static SymptomAnalysis parse(String json, List<String> allowed) {
        SymptomAnalysis result;
        try {
            result = new Gson().fromJson(json.trim(), SymptomAnalysis.class);
        } catch (JsonSyntaxException e) {
            return null;
        }
        if (result == null) {
            return null;
        }
        List<String> filtered = new ArrayList<>();
        for (String concern : result.getConcerns()) {
            if (allowed.contains(concern) && !filtered.contains(concern)) {
                filtered.add(concern);
            }
        }
        result.concerns = filtered;
        return result;
    }
}
