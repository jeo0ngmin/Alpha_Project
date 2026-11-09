package com.medimate.ui.recommend;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class HistoryTextTest {

    @Test
    public void emptyWhenNothingReported() {
        assertEquals("", RecommendReply.historyText(Collections.emptyList(), Collections.emptyList()));
    }

    @Test
    public void repeatsConditionsAndMedications() {
        assertEquals("말씀하신 지병(당뇨)과 드시는 약(메트포르민, 혈압약)이 있으시네요.",
                RecommendReply.historyText(Collections.singletonList("당뇨"),
                        Arrays.asList("메트포르민", "혈압약")));
        assertEquals("말씀하신 지병(고혈압)이 있으시네요.",
                RecommendReply.historyText(Collections.singletonList("고혈압"), Collections.emptyList()));
        assertEquals("말씀하신 드시는 약(아스피린)이 있으시네요.",
                RecommendReply.historyText(Collections.emptyList(), Collections.singletonList("아스피린")));
    }
}
