package com.medimate.recommend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class SymptomWarningTest {

    /** 실제 병원 안내 표: 모든 줄에 출처와 고정 문구가 있다 (README 8-7) */
    @Test
    public void warningsFileHasSources() throws Exception {
        try (Reader reader = new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("symptom_warnings.json"), StandardCharsets.UTF_8)) {
            List<SymptomWarning> warnings = SymptomWarning.load(reader);

            assertFalse(warnings.isEmpty());
            for (SymptomWarning w : warnings) {
                assertFalse(w.getId() + " 출처 없음", w.getSource().isEmpty());
                assertFalse(w.getId() + " 문구 없음", w.getMessage().isEmpty());
            }
        }
    }

    @Test
    public void dropsRowsWithoutSource() {
        List<SymptomWarning> warnings = SymptomWarning.load(new StringReader(
                "[{\"id\":\"a\",\"symptoms\":\"s\",\"message\":\"m\",\"source\":\"\"},"
                        + "{\"id\":\"b\",\"symptoms\":\"s\",\"message\":\"m\",\"source\":\"출처\"}]"));

        assertEquals(1, warnings.size());
        assertEquals("b", warnings.get(0).getId());
    }
}
