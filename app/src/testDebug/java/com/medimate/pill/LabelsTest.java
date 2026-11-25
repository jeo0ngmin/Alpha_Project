package com.medimate.pill;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LabelsTest {

    @Test
    public void objectParticleFollowsFinalConsonant() {
        assertEquals("을", Labels.objectParticle("데모노랑원형정"));
        assertEquals("를", Labels.objectParticle("아스피린프로텍트"));
        assertEquals("을(를)", Labels.objectParticle("Tylenol"));
    }
}
