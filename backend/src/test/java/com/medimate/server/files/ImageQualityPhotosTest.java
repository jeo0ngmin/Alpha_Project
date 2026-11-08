package com.medimate.server.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * 사진 품질 기준을 실제 사진으로 검증한다. 멀쩡한 사진을 거절하면 앱을 쓸 수 없으므로 오거절이 가장 중요하다.
 * 사진은 저장소에 없으므로 pill-classifier/data에 있을 때만 돈다.
 */
class ImageQualityPhotosTest {

    private static final Path DATA = Path.of("../pill-classifier/data");
    private static final int MAX_PER_FOLDER = 400;

    private static List<Path> photos(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.toString().toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|png)$"))
                    .sorted().limit(MAX_PER_FOLDER).toList();
        }
    }

    private static BufferedImage read(Path p) throws IOException {
        BufferedImage image = ImageIO.read(p.toFile());
        return image == null ? null : ImageProcessor.upright(image, 1);
    }

    private static BufferedImage darkened(BufferedImage src, double gain) {
        return mapped(src, c -> (int) (c * gain));
    }

    /** 조명에 하얗게 날아간 사진: 모든 픽셀이 흰색 가까이 몰린다. */
    private static BufferedImage washedOut(BufferedImage src) {
        return mapped(src, c -> 252 + c * 3 / 255);
    }

    private static BufferedImage mapped(BufferedImage src, java.util.function.IntUnaryOperator f) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int p = src.getRGB(x, y);
                out.setRGB(x, y, (f.applyAsInt((p >> 16) & 255) << 16) | (f.applyAsInt((p >> 8) & 255) << 8) | f.applyAsInt(p & 255));
            }
        }
        return out;
    }

    /** 초점이 크게 나간 사진: 작게 줄였다가 부드럽게 다시 키운다. */
    private static BufferedImage blurred(BufferedImage src, int factor) {
        int w = Math.max(2, src.getWidth() / factor);
        int h = Math.max(2, src.getHeight() / factor);
        return resize(resize(src, w, h), src.getWidth(), src.getHeight());
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static String spread(double[] values) {
        Arrays.sort(values);
        return String.format(Locale.ROOT, "min=%.0f p1=%.0f p50=%.0f p99=%.0f max=%.0f", values[0],
                values[(int) (values.length * 0.01)], values[values.length / 2], values[(int) (values.length * 0.99)],
                values[values.length - 1]);
    }

    private static int[] report(String name, List<Path> files) throws IOException {
        if (files.isEmpty()) {
            return new int[]{0, 0};
        }
        int rejected = 0;
        int n = 0;
        double[] bright = new double[files.size()];
        double[] dark = new double[files.size()];
        double[] sharp = new double[files.size()];
        for (Path p : files) {
            BufferedImage image = read(p);
            if (image == null) {
                continue;
            }
            ImageQuality.Stats s = ImageQuality.measure(image);
            bright[n] = s.p99();
            dark[n] = s.p05();
            sharp[n] = s.sharpness();
            n++;
            Optional<ImageQuality.Issue> issue = ImageQuality.issueOf(s);
            if (issue.isPresent()) {
                rejected++;
                System.out.printf(Locale.ROOT, "  거절 %s %s: %s (p99=%.0f p05=%.0f sharp=%.1f)%n", name, p.getFileName(),
                        issue.get(), s.p99(), s.p05(), s.sharpness());
            }
        }
        System.out.printf(Locale.ROOT, "[%s] %d장, 거절 %d장%n  p99  %s%n  p05  %s%n  sharp %s%n", name, n, rejected,
                spread(Arrays.copyOf(bright, n)), spread(Arrays.copyOf(dark, n)), spread(Arrays.copyOf(sharp, n)));
        return new int[]{rejected, n};
    }

    @Test
    void 멀쩡한_실제_사진은_거절하지_않고_망가뜨린_사진은_거절한다() throws IOException {
        List<Path> phone = photos(DATA.resolve("aihub/eval_photos"));
        assumeTrue(!phone.isEmpty(), "실제 사진이 없어 건너뜀");

        int[] a = report("AI Hub 촬영 사진", phone);
        int[] b = report("NIH 실사진", photos(DATA.resolve("nih_photos")));
        int[] c = report("식약처 참조 사진", photos(DATA.resolve("images")));
        int rejected = a[0] + b[0] + c[0];
        int total = a[1] + b[1] + c[1];
        System.out.printf(Locale.ROOT, "오거절 %d / %d = %.2f%%%n", rejected, total, 100.0 * rejected / total);

        // 망가뜨린 사진은 해당 이유로 거절돼야 한다. 표본만 본다
        int sampled = 0;
        int dark = 0;
        int bright = 0;
        int[] blurFactors = {4, 8, 16};
        int[] blur = new int[blurFactors.length];
        double[][] blurSharp = new double[blurFactors.length][phone.size() / 8 + 1];
        for (int i = 0; i < phone.size(); i += 8) {
            BufferedImage photo = read(phone.get(i));
            if (photo == null) {
                continue;
            }
            sampled++;
            dark += ImageQuality.check(darkened(photo, 0.08)).filter(x -> x == ImageQuality.Issue.DARK).isPresent() ? 1 : 0;
            bright += ImageQuality.check(washedOut(photo)).filter(x -> x == ImageQuality.Issue.BRIGHT).isPresent() ? 1 : 0;
            for (int f = 0; f < blurFactors.length; f++) {
                BufferedImage soft = blurred(photo, blurFactors[f]);
                blurSharp[f][sampled - 1] = ImageQuality.measure(soft).sharpness();
                blur[f] += ImageQuality.check(soft).filter(x -> x == ImageQuality.Issue.BLUR).isPresent() ? 1 : 0;
            }
        }
        for (int f = 0; f < blurFactors.length; f++) {
            System.out.printf(Locale.ROOT, "흐림 1/%d sharp %s%n", blurFactors[f], spread(Arrays.copyOf(blurSharp[f], sampled)));
        }
        System.out.printf(Locale.ROOT, "망가뜨린 사진 %d장: 어두움 %d, 과노출 %d, 흐림(1/4) %d, 흐림(1/8) %d, 흐림(1/16) %d%n", sampled, dark,
                bright, blur[0], blur[1], blur[2]);

        assertThat((double) rejected / total).isLessThanOrEqualTo(0.01);
        assertThat(dark).isEqualTo(sampled);
        assertThat(bright).isEqualTo(sampled);
        // 조금 흐린 사진은 정상 사진과 값이 겹쳐 통과시킨다. 거절은 심하게 흐린 사진만 한다
        assertThat(blur[2]).isEqualTo(sampled);
    }
}
