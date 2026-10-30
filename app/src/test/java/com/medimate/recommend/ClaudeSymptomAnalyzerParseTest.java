package com.medimate.recommend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class ClaudeSymptomAnalyzerParseTest {

    private static final SymptomAnalyzer.Vocabulary VOCAB = vocabulary();

    private static SymptomAnalyzer.Vocabulary vocabulary() {
        Map<String, String> concerns = new LinkedHashMap<>();
        for (String c : Arrays.asList("눈 건강", "피로", "장 건강")) {
            concerns.put(c, "");
        }
        Map<String, String> warnings = new LinkedHashMap<>();
        warnings.put("stomach_pain", "");
        Map<String, String> triage = new LinkedHashMap<>();
        triage.put("severe_dyspnea_unable_to_speak", "");
        Map<String, String> links = new LinkedHashMap<>();
        links.put("warfarin", "와파린");
        return new SymptomAnalyzer.Vocabulary(concerns, warnings, triage, links);
    }

    @Test
    public void keepsOnlyAllowedConcerns() {
        SymptomAnalysis result = ClaudeSymptomAnalyzer.parse(
                "{\"concerns\":[\"눈 건강\",\"탈모\",\"피로\",\"눈 건강\"],\"needs_more_info\":false,"
                        + "\"follow_up_question\":\"\",\"symptom_summary\":\"눈이 침침하고 피곤함\"}", VOCAB);

        assertEquals(Arrays.asList("눈 건강", "피로"), result.getConcerns());
        assertFalse(result.needsMoreInfo());
        assertEquals("눈이 침침하고 피곤함", result.getSymptomSummary());
    }

    @Test
    public void readsFollowUpQuestion() {
        SymptomAnalysis result = ClaudeSymptomAnalyzer.parse(
                "{\"concerns\":[],\"needs_more_info\":true,"
                        + "\"follow_up_question\":\" 어디가 불편하세요? \",\"symptom_summary\":\"몸이 안 좋음\"}", VOCAB);

        assertTrue(result.getConcerns().isEmpty());
        assertTrue(result.needsMoreInfo());
        assertEquals("어디가 불편하세요?", result.getFollowUpQuestion());
    }

    @Test
    public void readsConditionsAndMedications() {
        SymptomAnalysis result = ClaudeSymptomAnalyzer.parse(
                "{\"concerns\":[\"피로\"],\"needs_more_info\":false,\"follow_up_question\":\"\",\"unmatched\":false,"
                        + "\"symptom_summary\":\"피곤함\",\"conditions\":[\"당뇨\"],\"medications\":[\"혈압약\"]}", VOCAB);

        assertEquals(Arrays.asList("당뇨"), result.getConditions());
        assertEquals(Arrays.asList("혈압약"), result.getMedications());
        assertTrue(ClaudeSymptomAnalyzer.parse("{\"concerns\":[]}", VOCAB).getConditions().isEmpty());
    }

    @Test
    public void keepsOnlyKnownWarnings() {
        SymptomAnalysis result = ClaudeSymptomAnalyzer.parse(
                "{\"concerns\":[],\"warnings\":[\"stomach_pain\",\"chest_pain\"]}", VOCAB);

        assertEquals(Arrays.asList("stomach_pain"), result.getWarnings());
    }

    @Test
    public void keepsKnownTriageAndDowngradesUnknownLinks() {
        SymptomAnalysis result = ClaudeSymptomAnalyzer.parse(
                "{\"triage\":[\"severe_dyspnea_unable_to_speak\",\"made_up\"],"
                        + "\"links\":[{\"raw_name\":\"쿠마딘\",\"target_id\":\"warfarin\",\"user_confirmed\":true},"
                        + "{\"raw_name\":\"혈압약\",\"target_id\":\"losartan\",\"user_confirmed\":true},"
                        + "{\"raw_name\":\"타이레놀\",\"target_id\":\"not_listed\",\"user_confirmed\":false}]}", VOCAB);

        assertEquals(Arrays.asList("severe_dyspnea_unable_to_speak"), result.getTriage());
        assertEquals("warfarin", result.getLinks().get(0).getTargetId());
        assertTrue(result.getLinks().get(0).isUserConfirmed());
        // 목록에 없는 id로 연결하면 모름으로 바꾸고 확인도 지운다
        assertEquals(SymptomAnalysis.Link.UNCLEAR, result.getLinks().get(1).getTargetId());
        assertFalse(result.getLinks().get(1).isUserConfirmed());
        assertEquals(SymptomAnalysis.Link.NOT_LISTED, result.getLinks().get(2).getTargetId());
    }

    @Test
    public void returnsNullOnBrokenJson() {
        assertNull(ClaudeSymptomAnalyzer.parse("{\"concerns\":[", VOCAB));
        assertNull(ClaudeSymptomAnalyzer.parse("", VOCAB));
    }
}
