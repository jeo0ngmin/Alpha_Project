package com.medimate.gpt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.medimate.gpt.model.GptDrugList;

import org.junit.Test;

public class GptProcessorParseTest {

    private static final String JSON = "{\"drugs\":[{\"name\":\"타이레놀정500밀리그램\",\"dosage\":\"1정 3회 3일\","
            + "\"storage\":\"\"}],\"common_instructions\":\"하루 3번 식후 30분에 복용하세요\"}";

    @Test
    public void parsesPlainJson() {
        GptDrugList result = new GptProcessor().parse(JSON);

        assertEquals(1, result.getDrugs().size());
        assertEquals("타이레놀정500밀리그램", result.getDrugs().get(0).getName());
        assertEquals("1정 3회 3일", result.getDrugs().get(0).getDosage());
        assertEquals("", result.getDrugs().get(0).getStorage());
        assertEquals("하루 3번 식후 30분에 복용하세요", result.getCommonInstructions());
    }

    @Test
    public void stripsCodeFence() {
        GptDrugList result = new GptProcessor().parse("```json\n" + JSON + "\n```");

        assertEquals(1, result.getDrugs().size());
    }

    @Test
    public void emptyDrugsGivesEmptyList() {
        GptDrugList result = new GptProcessor().parse("{\"drugs\":[]}");

        assertTrue(result.getDrugs().isEmpty());
        assertEquals("", result.getCommonInstructions());
    }
}
