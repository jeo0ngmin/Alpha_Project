package com.medimate.recommend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ClaudeSymptomAnalyzerParseTest {

    private static final List<String> ALLOWED = Arrays.asList("눈 건강", "피로", "장 건강");

    @Test
    public void keepsOnlyAllowedConcerns() {
        SymptomAnalysis result = ClaudeSymptomAnalyzer.parse(
                "{\"concerns\":[\"눈 건강\",\"탈모\",\"피로\",\"눈 건강\"],\"needs_more_info\":false,"
                        + "\"follow_up_question\":\"\",\"symptom_summary\":\"눈이 침침하고 피곤함\"}", ALLOWED);

        assertEquals(Arrays.asList("눈 건강", "피로"), result.getConcerns());
        assertFalse(result.needsMoreInfo());
        assertEquals("눈이 침침하고 피곤함", result.getSymptomSummary());
    }

    @Test
    public void readsFollowUpQuestion() {
        SymptomAnalysis result = ClaudeSymptomAnalyzer.parse(
                "{\"concerns\":[],\"needs_more_info\":true,"
                        + "\"follow_up_question\":\" 어디가 불편하세요? \",\"symptom_summary\":\"몸이 안 좋음\"}", ALLOWED);

        assertTrue(result.getConcerns().isEmpty());
        assertTrue(result.needsMoreInfo());
        assertEquals("어디가 불편하세요?", result.getFollowUpQuestion());
    }

    @Test
    public void returnsNullOnBrokenJson() {
        assertNull(ClaudeSymptomAnalyzer.parse("{\"concerns\":[", ALLOWED));
        assertNull(ClaudeSymptomAnalyzer.parse("", ALLOWED));
    }
}
