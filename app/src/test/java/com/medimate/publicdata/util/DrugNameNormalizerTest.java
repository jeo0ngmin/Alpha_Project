package com.medimate.publicdata.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DrugNameNormalizerTest {

    @Test
    public void removesDose() {
        assertEquals("아모디핀정", DrugNameNormalizer.normalize("아모디핀정5mg"));
        assertEquals("타이레놀정", DrugNameNormalizer.normalize("타이레놀정 500밀리그램"));
        assertEquals("타이레놀정", DrugNameNormalizer.normalize("타이레놀정 500 밀리그램"));
        assertEquals("타이레놀정", DrugNameNormalizer.normalize("타이레놀정500밀리그람"));
        assertEquals("아세트아미노펜시럽", DrugNameNormalizer.normalize("아세트아미노펜시럽 10mL"));
    }

    @Test
    public void keepsNameWithoutDose() {
        assertEquals("코대원정", DrugNameNormalizer.normalize("코대원정"));
        assertEquals("", DrugNameNormalizer.normalize(null));
    }
}
