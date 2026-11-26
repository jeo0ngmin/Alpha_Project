package com.medimate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MedicineDetailSpeechTest {

    @Test
    public void shortTextIsReadWhole() {
        assertEquals("하루 2회 복용한다.", MedicineDetailActivity.spokenPart("하루 2회 복용한다."));
    }

    @Test
    public void longTextIsCutAtSentenceAndExplained() {
        StringBuilder body = new StringBuilder("1. 경고\n");
        for (int i = 0; i < 40; i++) {
            body.append(i).append(") 이 약을 먹기 전에 의사와 상의한다. ");
        }
        String spoken = MedicineDetailActivity.spokenPart(body.toString());
        assertTrue(spoken.startsWith("1. 경고 0) 이 약을"));
        assertTrue(spoken.endsWith("약사에게 물어봐 주세요."));
        assertTrue(spoken.length() < 400);
    }
}
