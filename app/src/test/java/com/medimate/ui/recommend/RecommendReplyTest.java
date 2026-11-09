package com.medimate.ui.recommend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.medimate.recommend.KnowledgeBase;
import com.medimate.recommend.KnowledgeMessages;
import com.medimate.recommend.RecommendEngine;
import com.medimate.recommend.SymptomAnalysis;
import com.medimate.recommend.SymptomWarning;

import org.junit.Before;
import org.junit.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/** 실제 assets(규칙 표·근거 규칙)로 답변 흐름을 확인한다 (README 8-8) */
public class RecommendReplyTest {

    private RecommendEngine engine;
    private KnowledgeBase knowledge;
    private RecommendReply reply;

    private Reader asset(String name) {
        return new InputStreamReader(getClass().getClassLoader().getResourceAsStream(name), StandardCharsets.UTF_8);
    }

    @Before
    public void setUp() throws Exception {
        try (Reader rules = asset("supplement_rules.json")) {
            engine = RecommendEngine.load(rules);
        }
        try (Reader rules = asset("knowledge_rules.jsonl");
             Reader sources = asset("knowledge_sources.json");
             Reader terms = asset("knowledge_terms.json")) {
            knowledge = KnowledgeBase.load(rules, sources, terms);
        }
        try (Reader w = asset("symptom_warnings.json")) {
            reply = new RecommendReply(engine, SymptomWarning.load(w), knowledge);
        }
    }

    private static SymptomAnalysis analysis(String json) {
        return new Gson().fromJson(json, SymptomAnalysis.class);
    }

    @Test
    public void emergencyGoesStraightTo119() {
        SymptomAnalysis a = analysis("{\"concerns\":[\"피로\"],\"triage\":[\"severe_dyspnea_unable_to_speak\"]}");

        assertFalse(reply.emergencyRules(a).isEmpty());
        assertTrue(reply.emergencyText(reply.emergencyRules(a)).contains("119"));
    }

    /** 응급 안내 표는 시작 자료 규칙이 없는 릴리스 빌드에서도 동작한다 */
    @Test
    public void strokeSignsGo119EvenWithoutKnowledge() throws Exception {
        RecommendReply release;
        try (Reader w = asset("symptom_warnings.json")) {
            release = new RecommendReply(engine, SymptomWarning.load(w), KnowledgeBase.empty());
        }
        SymptomAnalysis a = analysis("{\"concerns\":[],\"unmatched\":true,\"warnings\":[\"stroke_signs\"]}");

        String text = release.emergencyWarningText(a);
        assertTrue(text.contains("뇌졸중"));
        assertTrue(text.contains("119"));
        // 응급 문구는 추천 답변 끝에 붙는 병원 안내와 섞이지 않는다
        assertFalse(release.build(a).contains("뇌졸중"));
    }

    @Test
    public void hospitalWarningIsNotEmergency() {
        SymptomAnalysis a = analysis("{\"concerns\":[\"위 건강\"],\"warnings\":[\"stomach_pain\"]}");

        assertEquals("", reply.emergencyWarningText(a));
        assertTrue(reply.build(a).contains("병원에 내원하여"));
    }

    @Test
    public void medicalEvaluationReplacesRecommendation() {
        SymptomAnalysis a = analysis("{\"concerns\":[\"피로\"],\"triage\":[\"unexplained_persistent_fatigue\"]}");

        String text = reply.build(a);
        assertTrue(text.contains("먼저 진료를 받으세요"));
        assertFalse(text.contains("도움이 될 수 있는 성분"));
    }

    @Test
    public void confirmedWarfarinAddsOmega3Note() {
        SymptomAnalysis a = analysis("{\"concerns\":[\"혈행 개선\"],"
                + "\"links\":[{\"raw_name\":\"와파린\",\"target_id\":\"warfarin\",\"user_confirmed\":true}]}");

        assertTrue(reply.linksToConfirm(a).isEmpty());
        String text = reply.build(a);
        assertTrue(text.contains("⚠ EPA 및 DHA 함유 유지(오메가3) — 와파린"));
    }

    @Test
    public void unconfirmedLinkIsAskedThenTreatedAsUnknown() {
        SymptomAnalysis a = analysis("{\"concerns\":[\"혈행 개선\"],"
                + "\"links\":[{\"raw_name\":\"쿠마딘\",\"target_id\":\"warfarin\",\"user_confirmed\":false}]}");

        assertEquals(1, reply.linksToConfirm(a).size());
        String text = reply.build(a);
        assertFalse(text.contains("⚠"));
        assertTrue(text.contains("성분을 알 수 없는 것(쿠마딘)"));
    }

    @Test
    public void calciumWithLevothyroxineAsksAboutTiming() {
        SymptomAnalysis a = analysis("{\"concerns\":[\"뼈 건강\"],"
                + "\"links\":[{\"raw_name\":\"씬지로이드\",\"target_id\":\"levothyroxine\",\"user_confirmed\":true}]}");

        String text = reply.build(a);
        assertTrue(text.contains("⚠ 칼슘 — 레보티록신"));
        assertTrue(text.contains("복용 간격"));
    }

    @Test
    public void noRuleIsNotSafe() {
        SymptomAnalysis a = analysis("{\"concerns\":[\"눈 건강\"],"
                + "\"links\":[{\"raw_name\":\"타이레놀\",\"target_id\":\"not_listed\",\"user_confirmed\":false}]}");

        assertTrue(reply.build(a).contains("문제가 없다는 뜻은 아니니"));
    }

    /** 표에 없는 증상(두통)은 AI가 고른 성분을 증상 제목 아래 "성분명 / 효능 / 주의"로 보여 주고, "추천 없음" 안내는 하지 않는다 */
    @Test
    public void aiSuggestionsForSymptomOutsideTable() {
        SymptomAnalysis a = analysis("{\"concerns\":[],\"unmatched\":true,\"ai_suggestions\":["
                + "{\"symptom\":\"두통\",\"ingredient\":\"마그네슘\",\"reason\":\"근육과 신경 기능에 필요해요.\","
                + "\"caution\":\"신장 질환이 있으면 상담하세요.\",\"ingredient_id\":\"magnesium\"}]}");

        assertTrue(reply.hasCandidates(a));
        String text = reply.build(a);
        assertTrue(text.contains("[두통]\n• 마그네슘\n  효능: 근육과 신경 기능에 필요해요.\n  주의: 신장 질환이 있으면 상담하세요."));
        assertFalse(text.contains(RecommendReply.MSG_UNMATCHED));
    }

    /** "눈이 침침하고 머리가 아파요": 규칙 표 증상과 AI 증상을 각각 제목으로 묶어 둘 다 보여 준다 */
    @Test
    public void twoSymptomsAreGroupedSeparately() {
        SymptomAnalysis a = analysis("{\"concerns\":[\"눈 건강\"],\"unmatched\":true,\"ai_suggestions\":["
                + "{\"symptom\":\"두통\",\"ingredient\":\"마그네슘\",\"reason\":\"r\",\"caution\":\"c\",\"ingredient_id\":\"magnesium\"}]}");

        String text = reply.build(a);
        int eye = text.indexOf("[눈 건강]");
        int headache = text.indexOf("[두통]");
        assertTrue(eye >= 0 && headache > eye);
        assertTrue(text.indexOf("• 루테인") > eye && text.indexOf("• 루테인") < headache);
        assertTrue(text.indexOf("• 마그네슘") > headache);
        // 맨 앞에 빈 줄이 없고, 성분 하나(주의까지)가 끝나면 빈 줄로 나눈다
        assertTrue(text.startsWith("[눈 건강]\n• 루테인\n"));
        assertTrue(text.contains("\n\n• EPA 및 DHA 함유 유지(오메가3)\n"));
        assertTrue(text.contains("\n\n[두통]\n• 마그네슘\n"));
        assertFalse(text.contains("\n\n\n"));
        // 성분마다 반복되던 공전 문구 대신 끝에 한 번만
        assertFalse(text.contains("이상사례"));
        assertTrue(text.contains(RecommendReply.MSG_ADVERSE));
    }

    /** AI가 고른 성분도 확인된 약·질환과 근거 규칙으로 대조한다 */
    @Test
    public void aiSuggestionIsCheckedAgainstKnowledge() {
        SymptomAnalysis a = analysis("{\"concerns\":[],\"unmatched\":true,\"ai_suggestions\":["
                + "{\"ingredient\":\"마그네슘\",\"reason\":\"r\",\"caution\":\"\",\"ingredient_id\":\"magnesium\"}],"
                + "\"links\":[{\"raw_name\":\"신장이 안 좋아요\",\"target_id\":\"impaired_renal_function\","
                + "\"user_confirmed\":false}]}");

        assertEquals(1, reply.linksToConfirm(a).size());
        SymptomAnalysis confirmed = analysis("{\"concerns\":[],\"unmatched\":true,\"ai_suggestions\":["
                + "{\"ingredient\":\"마그네슘\",\"reason\":\"r\",\"caution\":\"\",\"ingredient_id\":\"magnesium\"}],"
                + "\"links\":[{\"raw_name\":\"신장이 안 좋아요\",\"target_id\":\"impaired_renal_function\","
                + "\"user_confirmed\":true}]}");
        assertTrue(reply.build(confirmed).contains("⚠ 마그네슘 — 신장기능 저하"));
    }

    /** 출처 줄은 화면·낭독에 넣지 않는다 (2026-10-30 결정) */
    @Test
    public void repliesHaveNoSourceLines() {
        SymptomAnalysis a = analysis("{\"concerns\":[\"혈행 개선\",\"뼈 건강\"],"
                + "\"links\":[{\"raw_name\":\"와파린\",\"target_id\":\"warfarin\",\"user_confirmed\":true}]}");

        String text = reply.build(a);
        assertFalse(text.contains("근거:"));
        assertFalse(text.contains("식품의약품안전처"));
        assertFalse(text.contains("NIH"));
    }

    /** 제품 링크는 규칙 표 + AI 성분으로 만들고, 진료 우선이면 만들지 않는다 (README 8-9) */
    @Test
    public void recommendedIngredientsForProductLinks() {
        SymptomAnalysis a = analysis("{\"concerns\":[\"눈 건강\"],\"unmatched\":true,\"ai_suggestions\":["
                + "{\"ingredient\":\"마그네슘\",\"reason\":\"r\",\"caution\":\"\",\"ingredient_id\":\"magnesium\"}]}");
        assertEquals(java.util.Arrays.asList("루테인", "EPA 및 DHA 함유 유지(오메가3)", "마그네슘"),
                reply.recommendedIngredients(a));

        SymptomAnalysis medical = analysis("{\"concerns\":[\"피로\"],\"triage\":[\"unexplained_persistent_fatigue\"]}");
        assertTrue(reply.recommendedIngredients(medical).isEmpty());
    }

    /** "소화가 안 되고 머리가 아파요" → "당뇨": 내부 지식 버전(starter-…)이나 "규칙" 같은 개발 용어가 답변에 나오지 않는다 */
    @Test
    public void noInternalLabelsInReplies() {
        SymptomAnalysis a = analysis("{\"concerns\":[\"위 건강\"],\"unmatched\":true,\"conditions\":[\"당뇨\"],"
                + "\"ai_suggestions\":[{\"symptom\":\"두통\",\"ingredient\":\"마그네슘\",\"reason\":\"r\",\"caution\":\"c\","
                + "\"ingredient_id\":\"magnesium\"}],"
                + "\"links\":[{\"raw_name\":\"당뇨\",\"target_id\":\"not_listed\",\"user_confirmed\":false}]}");

        String text = reply.build(a);
        assertTrue(text.contains("당뇨"));
        assertFalse(text.contains("starter"));
        assertFalse(text.contains(KnowledgeBase.VERSION));
        assertFalse(text.contains("규칙"));
        assertFalse(reply.emergencyText(new java.util.ArrayList<>()).contains("starter"));
    }

    @Test
    public void releaseBuildWithoutKnowledgeShowsNoRuleNotes() {
        RecommendReply plain = new RecommendReply(engine, new java.util.ArrayList<>(), KnowledgeBase.empty());
        SymptomAnalysis a = analysis("{\"concerns\":[\"혈행 개선\"],\"triage\":[\"severe_dyspnea_unable_to_speak\"],"
                + "\"links\":[{\"raw_name\":\"와파린\",\"target_id\":\"warfarin\",\"user_confirmed\":true}]}");

        assertTrue(plain.emergencyRules(a).isEmpty());
        String text = plain.build(a);
        assertFalse(text.contains("⚠"));
        assertFalse(text.contains("확인할 수 없었어요"));
    }
}
