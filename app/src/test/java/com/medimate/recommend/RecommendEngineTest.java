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
