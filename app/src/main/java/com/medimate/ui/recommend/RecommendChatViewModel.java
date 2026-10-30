package com.medimate.ui.recommend;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.medimate.BuildConfig;
import com.medimate.recommend.ClaudeSymptomAnalyzer;
import com.medimate.recommend.KnowledgeBase;
import com.medimate.recommend.KnowledgeRule;
import com.medimate.recommend.RecommendEngine;
import com.medimate.recommend.SymptomAnalysis;
import com.medimate.recommend.SymptomAnalyzer;
import com.medimate.recommend.SymptomWarning;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 영양제 추천 챗봇 (README 8-2). 사용자 말 → Claude가 증상 파악 → 응급이면 바로 119 안내 →
 * (부족하면 추가 질문) → 지병·약 질문 → (약·질환 연결 확인) → 규칙 표로 성분 결정 → 답변.
 * 추천을 한 번 내면 상담이 끝나고, 다음 입력은 새 상담으로 본다.
 * 제품 조회(건강기능식품 API)는 실제 응답을 받은 뒤 붙인다 (README 8-4).
 */
public class RecommendChatViewModel extends AndroidViewModel {

    private static final String TAG = "RecommendChat";

    static final String MSG_WELCOME = "어떤 건강 고민이 있는지 말씀해 주세요. 예를 들어 \"요즘 눈이 침침하고 피곤해요\"처럼요.";
    static final String MSG_RULES_FAILED = "추천 규칙을 불러오지 못했어요.";
    /** 추천 전에 한 번 묻는다 (README 8-2). 지병 때문에 생긴 증상일 수 있어서다 (예: 당뇨병성 신경병증의 손발 저림) */
    static final String MSG_HISTORY = "영양제를 알려 드리기 전에 여쭤볼게요. "
            + "평소 앓고 계신 병이 있거나 드시고 있는 약이 있으세요? 없으면 없다고 말씀해 주세요.";
    /** 한 상담에서 추가 질문을 하는 최대 횟수 (README 8-2) */
    static final int MAX_FOLLOW_UPS = 2;

    private final RecommendEngine engine;
    private final List<SymptomWarning> warnings;
    /** 시작 자료의 근거 규칙. 전문가 검토 전이라 디버그 빌드에서만 읽는다 (README 8-8, 정책 P10) */
    private final KnowledgeBase knowledge;
    private final RecommendReply reply;
    private final SymptomAnalyzer analyzer = new ClaudeSymptomAnalyzer();

    /** 이번 상담의 대화. 추천을 내면 비운다 */
    private final List<SymptomAnalyzer.Turn> conversation = new ArrayList<>();
    private int followUps = 0;
    /** 이번 상담에서 지병·복용 약을 물었는지 */
    private boolean historyAsked = false;
    /** 이번 상담에서 약·질환 연결을 확인받았는지 */
    private boolean confirmAsked = false;

    private final List<ChatMessage> messages = new ArrayList<>();
    private final MutableLiveData<List<ChatMessage>> messagesLive = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String> speak = new MutableLiveData<>();

    public RecommendChatViewModel(@NonNull Application application) {
        super(application);
        engine = loadEngine(application);
        warnings = loadWarnings(application);
        knowledge = BuildConfig.DEBUG ? loadKnowledge(application) : KnowledgeBase.empty();
        reply = engine == null ? null : new RecommendReply(engine, warnings, knowledge);
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

        analyzer.analyze(conversation, vocabulary(), new SymptomAnalyzer.Callback() {
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

    /**
     * 응급이면 바로 119 안내. 정보가 부족하면 추가 질문, 추천할 항목이 정해졌으면 지병·복용 약을 묻고,
     * 규칙에 걸릴 수 있는 약·질환 연결은 사용자에게 확인받은 뒤 답변하고 상담을 끝낸다.
     */
    void handle(SymptomAnalysis analysis) {
        List<KnowledgeRule> emergency = reply.emergencyRules(analysis);
        if (!emergency.isEmpty()) {
            finish(reply.emergencyText(emergency));
            return;
        }
        String question = analysis.getFollowUpQuestion();
        if (analysis.needsMoreInfo() && !question.isEmpty() && followUps < MAX_FOLLOW_UPS) {
            followUps++;
            ask(question);
            return;
        }
        boolean medical = !reply.medicalRules(analysis).isEmpty();
        boolean hasCandidates = !engine.recommend(analysis.getConcerns()).isEmpty();
        if (!medical && hasCandidates && !historyAsked) {
            historyAsked = true;
            ask(MSG_HISTORY);
            return;
        }
        List<SymptomAnalysis.Link> toConfirm = reply.linksToConfirm(analysis);
        if (!medical && !toConfirm.isEmpty() && !confirmAsked) {
            confirmAsked = true;
            ask(reply.confirmQuestion(toConfirm));
            return;
        }
        finish(reply.build(analysis));
    }

    private void ask(String question) {
        conversation.add(new SymptomAnalyzer.Turn(false, question));
        bot(question);
    }

    /** 답변을 내고 상담을 끝낸다 */
    private void finish(String text) {
        conversation.clear();
        followUps = 0;
        historyAsked = false;
        confirmAsked = false;
        bot(text);
    }

    private void bot(String text) {
        messages.add(new ChatMessage(ChatMessage.Role.BOT, text));
        messagesLive.setValue(messages);
        speak.setValue(text);
    }

    private SymptomAnalyzer.Vocabulary vocabulary() {
        Map<String, String> warningScopes = new LinkedHashMap<>();
        for (SymptomWarning warning : warnings) {
            warningScopes.put(warning.getId(), warning.getSymptoms());
        }
        return new SymptomAnalyzer.Vocabulary(engine.concernScopes(), warningScopes,
                knowledge.triageScopes(), knowledge.linkTargets());
    }

    private static RecommendEngine loadEngine(Application application) {
        try (Reader reader = asset(application, "supplement_rules.json")) {
            return RecommendEngine.load(reader);
        } catch (IOException | RuntimeException e) {
            android.util.Log.e(TAG, "supplement_rules.json 읽기 실패", e);
            return null;
        }
    }

    /** 병원 안내 표. 읽지 못하면 안내 없이 진행한다 */
    private static List<SymptomWarning> loadWarnings(Application application) {
        try (Reader reader = asset(application, "symptom_warnings.json")) {
            return SymptomWarning.load(reader);
        } catch (IOException | RuntimeException e) {
            android.util.Log.e(TAG, "symptom_warnings.json 읽기 실패", e);
            return new ArrayList<>();
        }
    }

    /** 근거 규칙. 읽지 못하면 규칙 없이 진행한다 */
    private static KnowledgeBase loadKnowledge(Application application) {
        try (Reader rules = asset(application, "knowledge_rules.jsonl");
             Reader sources = asset(application, "knowledge_sources.json");
             Reader terms = asset(application, "knowledge_terms.json")) {
            return KnowledgeBase.load(rules, sources, terms);
        } catch (IOException | RuntimeException e) {
            android.util.Log.e(TAG, "근거 규칙 읽기 실패", e);
            return KnowledgeBase.empty();
        }
    }

    private static Reader asset(Application application, String name) throws IOException {
        return new InputStreamReader(application.getAssets().open(name), StandardCharsets.UTF_8);
    }
}
