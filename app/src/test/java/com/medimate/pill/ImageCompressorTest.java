package com.medimate.pill;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ImageCompressorTest {

    @Test
    public void sampleSizeKeepsAtLeastTargetResolution() {
        assertEquals(2, ImageCompressor.sampleSize(4000, 3000, 1600)); // 4000/2 = 2000 ≥ 1600
        assertEquals(4, ImageCompressor.sampleSize(8000, 6000, 1600));
        assertEquals(1, ImageCompressor.sampleSize(1200, 900, 1600));
    }

    @Test
    public void targetSizeKeepsAspectRatio() {
        assertArrayEquals(new int[]{1600, 1200}, ImageCompressor.targetSize(2000, 1500, 1600));
        assertArrayEquals(new int[]{900, 1600}, ImageCompressor.targetSize(1800, 3200, 1600));
        assertArrayEquals(new int[]{800, 600}, ImageCompressor.targetSize(800, 600, 1600));
    }
}
