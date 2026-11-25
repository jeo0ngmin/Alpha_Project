package com.medimate.ui.schedule;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.LocalTime;

public class TimeChoiceDialogTest {

    @Test
    public void spokenTime() {
        assertEquals("오전 8시", TimeChoiceDialog.spoken(LocalTime.of(8, 0)));
        assertEquals("오후 6시 30분", TimeChoiceDialog.spoken(LocalTime.of(18, 30)));
        assertEquals("낮 12시", TimeChoiceDialog.spoken(LocalTime.of(12, 0)));
        assertEquals("밤 12시 10분", TimeChoiceDialog.spoken(LocalTime.of(0, 10)));
    }
}
