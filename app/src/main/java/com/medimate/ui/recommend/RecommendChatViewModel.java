package com.medimate.ui.recommend;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.medimate.recommend.ClaudeSymptomAnalyzer;
import com.medimate.recommend.RecommendEngine;
import com.medimate.recommend.RecommendRule;
import com.medimate.recommend.SymptomAnalysis;
import com.medimate.recommend.SymptomAnalyzer;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 영양제 추천 챗봇 (README 8-2). 사용자 말 → Claude가 증상 파악 (부족하면 추가 질문) → 규칙 표로 성분 결정 → 답변.
 * 추천을 한 번 내면 상담이 끝나고, 다음 입력은 새 상담으로 본다.
 * 제품 조회(건강기능식품 API)는 실제 응답을 받은 뒤 붙인다 (README 8-4).
 */
public class RecommendChatViewModel extends AndroidViewModel {

    static final String DISCLAIMER = "이 정보는 의학적 조언이 아닙니다. 복용 전에 의사나 약사와 상담하세요.";
    static final String MSG_WELCOME = "어떤 건강 고민이 있는지 말씀해 주세요. 예를 들어 \"요즘 눈이 침침하고 피곤해요\"처럼요.";
    static final String MSG_RULES_FAILED = "추천 규칙을 불러오지 못했어요.";
    static final String MSG_UNMATCHED = "말씀하신 증상 중 일부는 아직 추천할 수 있는 항목이 없어요. "
            + "증상이 계속되거나 심하면 의사나 약사와 상담하세요.";
    /** 한 상담에서 추가 질문을 하는 최대 횟수 (README 8-2) */
    static final int MAX_FOLLOW_UPS = 2;

    private final RecommendEngine engine;
    private final SymptomAnalyzer analyzer = new ClaudeSymptomAnalyzer();

    /** 이번 상담의 대화. 추천을 내면 비운다 */
    private final List<SymptomAnalyzer.Turn> conversation = new ArrayList<>();
    private int followUps = 0;

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
        conversation.add(new SymptomAnalyzer.Turn(true, text));

        analyzer.analyze(conversation, engine.concernScopes(), new SymptomAnalyzer.Callback() {
            @Override
            public void onResult(SymptomAnalysis analysis) {
                isLoading.setValue(false);
                handle(analysis);
            }

            @Override
            public void onError(String message) {
                isLoading.setValue(false);
                // 실패한 말은 대화에서 빼서, 다시 말하면 그 말로 이어 가게 한다
                conversation.remove(conversation.size() - 1);
                bot(message);
            }
        });
    }

    /** 정보가 부족하면 추가 질문, 아니면 규칙 표로 추천하고 상담을 끝낸다 */
    void handle(SymptomAnalysis analysis) {
        String question = analysis.getFollowUpQuestion();
        if (analysis.needsMoreInfo() && !question.isEmpty() && followUps < MAX_FOLLOW_UPS) {
            followUps++;
            conversation.add(new SymptomAnalyzer.Turn(false, question));
            bot(question);
            return;
        }
        conversation.clear();
        followUps = 0;
        bot(buildReply(analysis.getConcerns(), analysis.isUnmatched()));
    }

    /**
     * 규칙 표 내용으로만 답을 만든다. 면책 문구는 항상 붙인다 (README 8-1).
     * unmatched면 표에 없는 증상이 있다는 것과 상담 안내를 덧붙인다 (README 8-2).
     */
    String buildReply(List<String> concerns, boolean unmatched) {
        List<RecommendRule> rules = engine.recommend(concerns);
        String available = "지금 추천할 수 있는 고민은 " + String.join(", ", engine.concerns()) + "이에요.";
        if (rules.isEmpty()) {
            if (unmatched) {
                return MSG_UNMATCHED + "\n" + available;
            }
            return "말씀하신 증상에 맞는 항목을 찾지 못했어요. " + available + " 이 중에서 말씀해 주세요.";
        }
        StringBuilder sb = new StringBuilder();
        if (unmatched) {
            sb.append(MSG_UNMATCHED).append("\n\n");
        }
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
