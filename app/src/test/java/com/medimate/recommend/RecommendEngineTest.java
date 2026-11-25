package com.medimate.recommend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RecommendEngineTest {

    private RecommendEngine loadAssets() throws Exception {
        try (Reader reader = new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("supplement_rules.json"), StandardCharsets.UTF_8)) {
            return RecommendEngine.load(reader);
        }
    }

    /** 실제 규칙 표: 모든 규칙에 출처가 있고 고민이 5개 이상이다 (README 8-5) */
    @Test
    public void rulesFileHasSourcesAndAtLeastFiveConcerns() throws Exception {
        RecommendEngine engine = loadAssets();

        assertTrue(engine.concerns().size() >= 5);
        for (RecommendRule rule : engine.recommend(engine.concerns())) {
            assertFalse(rule.getIngredient() + " 출처 없음", rule.getSource().isEmpty());
        }
    }

    /** 화면·낭독 문장은 -요체로 통일한다 (2026-10-30 사용자 요청). "~것.", "~있음" 같은 공전 말투가 남지 않게 */
    @Test
    public void ruleTextsUsePoliteStyle() throws Exception {
        RecommendEngine engine = loadAssets();
        for (RecommendRule rule : engine.recommend(engine.concerns())) {
            assertTrue(rule.getIngredient() + " 효능: " + rule.getReason(), rule.getReason().endsWith("요."));
            String caution = rule.getCaution();
            assertTrue(rule.getIngredient() + " 주의: " + caution, caution.isEmpty() || caution.endsWith("요."));
            assertFalse(rule.getIngredient() + " 주의에 공전 말투", caution.contains("것.") || caution.contains("있음"));
        }
    }

    /** 주의사항은 근거 출처가 있을 때만 보인다 */
    @Test
    public void cautionNeedsSource() {
        List<RecommendRule> rules = RecommendEngine.load(new StringReader(
                "[{\"concern\":\"c\",\"ingredient\":\"a\",\"reason\":\"r\",\"source\":\"s\","
                        + "\"caution\":\"주의\",\"cautionSource\":\"\"},"
                        + "{\"concern\":\"c\",\"ingredient\":\"b\",\"reason\":\"r\",\"source\":\"s\","
                        + "\"caution\":\"주의\",\"cautionSource\":\"출처\"}]"))
                .recommend(Collections.singletonList("c"));

        assertEquals("", rules.get(0).getCaution());
        assertEquals("주의", rules.get(1).getCaution());
    }

    /** 고민마다 범위 설명이 붙고, 같은 고민의 규칙 설명은 이어 붙는다 */
    @Test
    public void concernScopesJoinReasonsPerConcern() throws Exception {
        RecommendEngine engine = loadAssets();
        java.util.Map<String, String> scopes = engine.concernScopes();

        assertEquals(engine.concerns(), new java.util.ArrayList<>(scopes.keySet()));
        for (String scope : scopes.values()) {
            assertFalse(scope.isEmpty());
        }
        assertTrue(scopes.get("눈 건강").contains(" / "));
    }

    @Test
    public void matchesConcernsInTableOrder() throws Exception {
        List<RecommendRule> rules = loadAssets().recommend(Arrays.asList("피로", "눈 건강"));

        assertEquals("루테인", rules.get(0).getIngredient());
        assertEquals("비타민B1", rules.get(2).getIngredient());
    }

    @Test
    public void unknownConcernGivesEmpty() throws Exception {
        assertTrue(loadAssets().recommend(Collections.singletonList("없는 고민")).isEmpty());
    }

    /** 출처가 빈 규칙은 읽을 때 걸러 낸다 (README 8-1) */
    @Test
    public void dropsRulesWithoutSource() {
        RecommendEngine engine = RecommendEngine.load(new StringReader(
                "[{\"concern\":\"피로\",\"ingredient\":\"X\",\"reason\":\"r\",\"source\":\"\"},"
                        + "{\"concern\":\"피로\",\"ingredient\":\"Y\",\"reason\":\"r\",\"source\":\"기관\"}]"));

        List<RecommendRule> rules = engine.recommend(Collections.singletonList("피로"));
        assertEquals(1, rules.size());
        assertEquals("Y", rules.get(0).getIngredient());
    }
}
