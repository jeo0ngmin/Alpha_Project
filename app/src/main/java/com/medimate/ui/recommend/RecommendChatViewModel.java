package com.medimate.ui.recommend;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.medimate.recommend.ConcernClassifier;
import com.medimate.recommend.RecommendEngine;
import com.medimate.recommend.RecommendRule;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 영양제 추천 챗봇 (README 8-2). 사용자 말 → GPT가 고민 항목으로 분류 → 규칙 표로 성분 결정 → 답변.
 * 제품 조회(건강기능식품 API)는 실제 응답을 받은 뒤 붙인다 (README 8-4).
 */
public class RecommendChatViewModel extends AndroidViewModel {

    static final String DISCLAIMER = "이 정보는 의학적 조언이 아닙니다. 복용 전에 의사나 약사와 상담하세요.";
    static final String MSG_WELCOME = "어떤 건강 고민이 있는지 말씀해 주세요. 예를 들어 \"요즘 눈이 침침하고 피곤해요\"처럼요.";
    static final String MSG_RULES_FAILED = "추천 규칙을 불러오지 못했어요.";

    private final RecommendEngine engine;
    private final ConcernClassifier classifier = new ConcernClassifier();

    private final List<ChatMessage> messages = new ArrayList<>();
    private final MutableLiveData<List<ChatMessage>> messagesLive = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String> speak = new MutableLiveData<>();

    public RecommendChatViewModel(@NonNull Application application) {
        super(application);
        engine = loadEngine(application);
        bot(engine == null ? MSG_RULES_FAILED : MSG_WELCOME);
    }

    public LiveData<List<ChatMessage>> getMessages() {
        return messagesLive;
    }

    public LiveData<Boolean> getIsLoading() {
        return isLoading;
    }

    /** 마지막 봇 답변. 화면이 TTS로 읽어 준다 */
    public LiveData<String> getSpeak() {
        return speak;
    }

    public void send(String userText) {
        String text = userText.trim();
        if (text.isEmpty() || engine == null || Boolean.TRUE.equals(isLoading.getValue())) {
            return;
        }
        messages.add(new ChatMessage(ChatMessage.Role.USER, text));
        messagesLive.setValue(messages);
        isLoading.setValue(true);

        classifier.classify(text, engine.concerns(), new ConcernClassifier.ClassifyCallback() {
            @Override
            public void onResult(List<String> concerns) {
                isLoading.setValue(false);
                bot(buildReply(concerns));
            }

            @Override
            public void onError(String message) {
                isLoading.setValue(false);
                bot(message);
            }
        });
    }

    /** 규칙 표 내용으로만 답을 만든다. 면책 문구는 항상 붙인다 (README 8-1) */
    String buildReply(List<String> concerns) {
        List<RecommendRule> rules = engine.recommend(concerns);
        if (rules.isEmpty()) {
            return "말씀하신 증상에 맞는 항목을 찾지 못했어요. "
                    + String.join(", ", engine.concerns()) + " 중에서 말씀해 주세요.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(", ", concerns)).append("에 도움이 될 수 있는 성분이에요.\n");
        for (RecommendRule rule : rules) {
            sb.append("\n• ").append(rule.getIngredient()).append(": ").append(rule.getReason())
              .append("\n  (근거: ").append(rule.getSource()).append(")\n");
        }
        sb.append("\n").append(DISCLAIMER);
        return sb.toString();
    }

    private void bot(String text) {
        messages.add(new ChatMessage(ChatMessage.Role.BOT, text));
        messagesLive.setValue(messages);
        speak.setValue(text);
    }

    private static RecommendEngine loadEngine(Application application) {
        try (Reader reader = new InputStreamReader(
                application.getAssets().open("supplement_rules.json"), StandardCharsets.UTF_8)) {
            return RecommendEngine.load(reader);
        } catch (IOException | RuntimeException e) {
            android.util.Log.e("RecommendChat", "supplement_rules.json 읽기 실패", e);
            return null;
        }
    }
}
