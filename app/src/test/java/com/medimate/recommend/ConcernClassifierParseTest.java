package com.medimate.recommend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ConcernClassifierParseTest {

    private static final List<String> ALLOWED = Arrays.asList("눈 건강", "피로", "장 건강");

    @Test
    public void keepsOnlyAllowedConcerns() {
        List<String> result = new ConcernClassifier().parse(
                "{\"concerns\":[\"눈 건강\",\"탈모\",\"피로\",\"눈 건강\"]}", ALLOWED);

        assertEquals(Arrays.asList("눈 건강", "피로"), result);
    }

    @Test
    public void stripsCodeFenceAndHandlesEmpty() {
        assertTrue(new ConcernClassifier().parse("```json\n{\"concerns\":[]}\n```", ALLOWED).isEmpty());
        assertTrue(new ConcernClassifier().parse("{}", ALLOWED).isEmpty());
    }
}
