package com.medimate.server.files;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Optional;

/**
 * 업로드 사진의 밝기·선명도 검사.
 * 알약은 사진의 일부만 차지하고 바탕은 어두울 수도, 흰 종이일 수도 있어서 사진 전체 평균으로 판단하면 멀쩡한 사진을 거절한다.
 * 그래서 가장 밝은 부분과 가장 어두운 부분, 가장 선명한 영역만 본다. 기준값은 실제 사진으로 맞췄다 (ImageQualityPhotosTest).
 */
public final class ImageQuality {

    public enum Issue { DARK, BRIGHT, BLUR }

    /** 상위 1% 밝기가 이보다 어두우면 알약도 안 보이는 사진이다. */
    static final double DARK_P99 = 40;
    /** 하위 5% 밝기까지 이보다 밝으면 사진 전체가 하얗게 날아간 것이다. */
    static final double BRIGHT_P05 = 250;
    /** 가장 선명한 격자 칸의 라플라시안 분산이 이보다 작으면 초점이 어디에도 맞지 않은 것이다. */
    static final double BLUR_SHARPNESS = 5;

    private static final int ANALYSIS_LONG_SIDE = 1024;
    private static final int GRID = 8;

    private ImageQuality() {
    }

    public record Stats(double p99, double p05, double sharpness) {
    }

    public static Optional<Issue> check(BufferedImage image) {
        return issueOf(measure(image));
    }

    static Optional<Issue> issueOf(Stats s) {
        if (s.p99() < DARK_P99) {
            return Optional.of(Issue.DARK);
        }
        if (s.p05() > BRIGHT_P05) {
            return Optional.of(Issue.BRIGHT);
        }
        if (s.sharpness() < BLUR_SHARPNESS) {
            return Optional.of(Issue.BLUR);
        }
        return Optional.empty();
    }

    public static Stats measure(BufferedImage image) {
        BufferedImage small = shrink(image);
        int w = small.getWidth();
        int h = small.getHeight();
        int[] luma = new int[w * h];
        int[] rgb = small.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i];
            luma[i] = (299 * ((p >> 16) & 255) + 587 * ((p >> 8) & 255) + 114 * (p & 255)) / 1000;
        }
        int[] sorted = luma.clone();
        Arrays.sort(sorted);
        double p99 = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.99))];
        double p05 = sorted[(int) (sorted.length * 0.05)];
        return new Stats(p99, p05, sharpestTile(luma, w, h));
    }

    private static BufferedImage shrink(BufferedImage image) {
        int longSide = Math.max(image.getWidth(), image.getHeight());
        if (longSide <= ANALYSIS_LONG_SIDE) {
            return image;
        }
        double scale = (double) ANALYSIS_LONG_SIDE / longSide;
        int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** 사진을 격자로 나눠 칸마다 라플라시안 분산을 구하고 가장 큰 값을 돌려준다. 알약이 작아도 그 칸의 값은 크다. */
    private static double sharpestTile(int[] luma, int w, int h) {
        if (w < 3 || h < 3) {
            return 0;
        }
        int tileW = Math.max(3, w / GRID);
        int tileH = Math.max(3, h / GRID);
        double best = 0;
        for (int ty = 0; ty < h; ty += tileH) {
            for (int tx = 0; tx < w; tx += tileW) {
                double sum = 0;
                double squares = 0;
                int n = 0;
                for (int y = Math.max(1, ty); y < Math.min(h - 1, ty + tileH); y++) {
                    for (int x = Math.max(1, tx); x < Math.min(w - 1, tx + tileW); x++) {
                        int i = y * w + x;
                        int lap = luma[i - 1] + luma[i + 1] + luma[i - w] + luma[i + w] - 4 * luma[i];
                        sum += lap;
                        squares += (double) lap * lap;
                        n++;
                    }
                }
                if (n > 0) {
                    double mean = sum / n;
                    best = Math.max(best, squares / n - mean * mean);
                }
            }
        }
        return best;
    }
}
