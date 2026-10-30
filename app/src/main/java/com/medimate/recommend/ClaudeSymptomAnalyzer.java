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
 * 고를 수 있는 이름은 모두 JSON 스키마의 enum으로 묶어 표에 없는 이름이 나오지 않게 한다.
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
    public void analyze(List<Turn> conversation, Vocabulary vocabulary, Callback callback) {
        if (BuildConfig.CLAUDE_API_KEY.isEmpty()) {
            Log.e(TAG, "secrets.properties에 CLAUDE_API_KEY가 없다");
            callback.onError(MSG_NO_KEY);
            return;
        }
        List<Turn> turns = new ArrayList<>(conversation);
        executor.execute(() -> {
            try {
                SymptomAnalysis result = request(turns, vocabulary);
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

    private SymptomAnalysis request(List<Turn> turns, Vocabulary vocabulary) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(4096L)
                .system(systemPrompt(vocabulary))
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.LOW)
                        .format(JsonOutputFormat.builder().schema(schema(vocabulary)).build())
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
        SymptomAnalysis result = parse(text.toString(), vocabulary);
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

    /**
     * {CONCERNS} 고민 목록(이름: 범위), {WARNINGS} 병원 안내 목록(이름: 증상),
     * {TRIAGE} 응급·진료 확인 목록(이름: 상황), {LINKS} 약·질환 연결 목록(id: 이름)이 들어간다.
     */
    static final String SYSTEM_PROMPT = "너는 건강기능식품 추천 앱의 상담 도우미다. 사용자는 주로 고령층과 시각장애인이다.\n"
            + "할 일: 대화에서 사용자의 증상을 파악해 아래 고민 목록의 항목으로 바꾼다. "
            + "추천 성분은 앱이 규칙 표로 정하므로 너는 정하지 않는다.\n"
            + "고민 목록 (이름: 그 항목이 다루는 범위):\n"
            + "{CONCERNS}\n"
            + "병원 안내 목록 (이름: 그 안내가 다루는 증상):\n"
            + "{WARNINGS}\n"
            + "응급·진료 확인 목록 (이름: 해당하는 상황):\n"
            + "{TRIAGE}\n"
            + "약·질환 연결 목록 (id: 이름):\n"
            + "{LINKS}\n"
            + "규칙:\n"
            + "- 매 턴 가장 먼저, 사용자가 말한 상황이 응급·진료 확인 목록에 해당하는지 본다. "
            + "해당하면 triage에 그 이름을 넣는다. 사용자가 그런 증상이 없다고 했으면 넣지 않는다.\n"
            + "- concerns에는 목록의 이름만 글자 그대로 넣는다. 해당하는 것이 여러 개면 모두 넣는다.\n"
            + "- 증상이 그 항목의 범위에 실제로 들어갈 때만 고른다. 부위가 가깝거나 비슷해 보인다고 "
            + "넓혀 고르지 않는다. 예를 들어 속쓰림, 위경련, 명치 통증은 위(胃)의 증상이라 "
            + "배변·장내 유익균을 다루는 장 건강에 넣지 않는다.\n"
            + "- 사용자가 말한 증상 중 목록 범위에 들지 않는 것이 하나라도 있으면 unmatched를 true로 둔다. "
            + "범위에 드는 증상은 그대로 concerns에 넣는다.\n"
            + "- 증상은 분명한데 목록에 맞는 항목이 없을 때는 더 묻지 않는다. "
            + "concerns를 비우고 unmatched를 true, needs_more_info를 false로 둔다.\n"
            + "- 한 항목이라도 분명히 해당하면 needs_more_info는 false로 두고 더 묻지 않는다.\n"
            + "- 증상이 모호해서 어느 항목인지 고를 수 없을 때만 needs_more_info를 true로 두고, "
            + "follow_up_question에 질문 한 개를 쓴다. 질문은 증상(어디가, 어떻게, 언제부터)만 묻는 "
            + "짧은 존댓말로, 소리 내어 읽어도 알아듣기 쉽게 쓴다.\n"
            + "- 건강 고민과 관계없는 말이면 concerns를 비우고 needs_more_info와 unmatched는 false로 둔다.\n"
            + "- 진단, 성분, 제품, 복용법, 건강 조언은 어디에도 쓰지 않는다.\n"
            + "- symptom_summary에는 사용자가 말한 증상을 한 줄로 정리한다. 말하지 않은 내용은 넣지 않는다.\n"
            + "- 앱이 추천 전에 평소 지병과 복용 중인 약을 묻는다. 사용자가 말한 지병은 conditions에, "
            + "약은 medications에 사용자가 말한 대로 짧게 넣는다. 추측해서 넣지 않는다. "
            + "없다고 했거나 아직 말하지 않았으면 빈 배열로 둔다.\n"
            + "- 지병이나 약이 있다고만 하고 무엇인지 말하지 않았으면 needs_more_info를 true로 두고 "
            + "무엇인지 묻는다. 지병과 약에 대해서는 이것 말고 더 묻지 않는다.\n"
            + "- 사용자가 말한 약·지병·식사 방식·예정된 검사마다 links에 하나씩 넣는다. raw_name은 사용자가 말한 그대로 쓴다. "
            + "그 성분이나 상태임이 분명할 때만(상품명이라도 성분이 분명한 경우 포함) target_id에 연결 목록의 id를 넣는다. "
            + "'혈압약', '흰 알약'처럼 종류나 모양만 말해 성분을 알 수 없으면 unclear, "
            + "성분이나 상태는 분명하지만 연결 목록에 없으면 not_listed로 둔다. 색·모양·브랜드만으로 성분을 짐작하지 않는다.\n"
            + "- user_confirmed는 앱이 연결이 맞는지 묻고 사용자가 맞다고 답한 항목만 true로 둔다. "
            + "사용자가 아니라고 하거나 정정하면 새 정보로 고친다. 앞뒤가 다르면 더 최근에 말한 내용을 따른다.\n"
            + "- 사용자가 말한 증상이 병원 안내 목록의 증상에 해당하면 warnings에 그 이름을 넣는다. "
            + "고민 항목 선택과 따로 판단하고, 해당하지 않으면 빈 배열로 둔다. 안내 문구는 앱이 붙이므로 쓰지 않는다.\n"
            + "- needs_more_info가 false면 follow_up_question은 빈 문자열로 둔다.";

    static String systemPrompt(Vocabulary v) {
        return SYSTEM_PROMPT.replace("{CONCERNS}", bulletList(v.concerns))
                .replace("{WARNINGS}", bulletList(v.warnings))
                .replace("{TRIAGE}", bulletList(v.triage))
                .replace("{LINKS}", bulletList(v.linkTargets));
    }

    private static String bulletList(Map<String, String> entries) {
        if (entries.isEmpty()) {
            return "(없음)";
        }
        StringBuilder list = new StringBuilder();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (list.length() > 0) {
                list.append('\n');
            }
            list.append("- ").append(e.getKey()).append(": ").append(e.getValue());
        }
        return list.toString();
    }

    /** 고를 수 있는 이름을 enum으로 묶은 출력 스키마 */
    static JsonOutputFormat.Schema schema(Vocabulary v) {
        List<String> linkIds = new ArrayList<>(v.linkTargets.keySet());
        linkIds.add(SymptomAnalysis.Link.UNCLEAR);
        linkIds.add(SymptomAnalysis.Link.NOT_LISTED);

        Map<String, Object> link = map(
                "type", "object",
                "properties", map(
                        "raw_name", map("type", "string"),
                        "target_id", enumOf(linkIds),
                        "user_confirmed", map("type", "boolean")),
                "required", Arrays.asList("raw_name", "target_id", "user_confirmed"),
                "additionalProperties", false);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("concerns", arrayOf(enumOf(new ArrayList<>(v.concerns.keySet()))));
        properties.put("needs_more_info", map("type", "boolean"));
        properties.put("follow_up_question", map("type", "string"));
        properties.put("unmatched", map("type", "boolean"));
        properties.put("symptom_summary", map("type", "string"));
        properties.put("conditions", arrayOf(map("type", "string")));
        properties.put("medications", arrayOf(map("type", "string")));
        properties.put("warnings", arrayOf(enumOf(new ArrayList<>(v.warnings.keySet()))));
        properties.put("triage", arrayOf(enumOf(new ArrayList<>(v.triage.keySet()))));
        properties.put("links", arrayOf(link));

        return JsonOutputFormat.Schema.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(properties))
                .putAdditionalProperty("required", JsonValue.from(new ArrayList<>(properties.keySet())))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build();
    }

    /** 문자열 enum. 고를 이름이 없으면 enum 없이 문자열로 두고, parse에서 걸러 낸다 */
    private static Map<String, Object> enumOf(List<String> values) {
        return values.isEmpty() ? map("type", "string") : map("type", "string", "enum", values);
    }

    private static Map<String, Object> arrayOf(Map<String, Object> items) {
        return map("type", "array", "items", items);
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    /** 응답 JSON을 읽고, 목록에 없는 이름은 버린다. 연결 대상이 목록에 없으면 unclear로 바꾼다. 파싱 실패면 null */
    static SymptomAnalysis parse(String json, Vocabulary v) {
        SymptomAnalysis result;
        try {
            result = new Gson().fromJson(json.trim(), SymptomAnalysis.class);
        } catch (JsonSyntaxException e) {
            return null;
        }
        if (result == null) {
            return null;
        }
        result.concerns = keepAllowed(result.getConcerns(), v.concerns.keySet());
        result.warnings = keepAllowed(result.getWarnings(), v.warnings.keySet());
        result.triage = keepAllowed(result.getTriage(), v.triage.keySet());
        List<SymptomAnalysis.Link> links = new ArrayList<>();
        for (SymptomAnalysis.Link link : result.getLinks()) {
            String id = link.getTargetId();
            boolean known = v.linkTargets.containsKey(id) || SymptomAnalysis.Link.NOT_LISTED.equals(id);
            links.add(known ? link
                    : new SymptomAnalysis.Link(link.getRawName(), SymptomAnalysis.Link.UNCLEAR, false));
        }
        result.links = links;
        return result;
    }

    private static List<String> keepAllowed(List<String> names, java.util.Collection<String> allowed) {
        List<String> filtered = new ArrayList<>();
        for (String name : names) {
            if (allowed.contains(name) && !filtered.contains(name)) {
                filtered.add(name);
            }
        }
        return filtered;
    }
}
