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
        assertTrue(text.contains(KnowledgeMessages.UNREVIEWED));
    }

    @Test
    public void unconfirmedLinkIsAskedThenTreatedAsUnknown() {
        SymptomAnalysis a = analysis("{\"concerns\":[\"혈행 개선\"],"
                + "\"links\":[{\"raw_name\":\"쿠마딘\",\"target_id\":\"warfarin\",\"user_confirmed\":false}]}");

        assertEquals(1, reply.linksToConfirm(a).size());
        String text = reply.build(a);
        assertFalse(text.contains("⚠"));
        assertTrue(text.contains("성분을 확인하지 못한 것(쿠마딘)"));
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

        assertTrue(reply.build(a).contains("'함께 드셔도 안전하다'는 뜻이 아니에요"));
    }

    @Test
    public void releaseBuildWithoutKnowledgeShowsNoRuleNotes() {
        RecommendReply plain = new RecommendReply(engine, new java.util.ArrayList<>(), KnowledgeBase.empty());
        SymptomAnalysis a = analysis("{\"concerns\":[\"혈행 개선\"],\"triage\":[\"severe_dyspnea_unable_to_speak\"],"
                + "\"links\":[{\"raw_name\":\"와파린\",\"target_id\":\"warfarin\",\"user_confirmed\":true}]}");

        assertTrue(plain.emergencyRules(a).isEmpty());
        String text = plain.build(a);
        assertFalse(text.contains("⚠"));
        assertFalse(text.contains(KnowledgeMessages.UNREVIEWED));
    }
}
