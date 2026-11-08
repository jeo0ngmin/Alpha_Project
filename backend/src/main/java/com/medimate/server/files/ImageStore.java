package com.medimate.server.files;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.medimate.server.common.ApiException;
import com.medimate.server.common.Ids;
import com.medimate.server.common.MedimateProperties;
import com.medimate.server.common.Times;

/**
 * 사진을 서버 디스크에 보관한다. 파일 이름이 곧 ID이고 파일 수정 시각으로 만료를 판단한다.
 * ID 형식을 먼저 검사하므로 경로 조작은 들어올 수 없다.
 */
@Component
public class ImageStore {

    public record Saved(String fileId, long sizeBytes, int width, int height, Instant signedUrlExpiresAt,
                        String signature, Instant expiresAt) {
    }

    private static final Logger log = LoggerFactory.getLogger(ImageStore.class);
    private static final Pattern FILE_ID = Pattern.compile("f_[0-9A-Z]{26}");
    private static final Pattern STORED_NAME = Pattern.compile("f_[0-9A-Z]{26}\\.jpg");
    static final int MAX_BYTES = 10 * 1024 * 1024;

    private final Path dir;
    private final Duration retention;
    private final Duration signedUrlLife;
    /** 서명 URL은 10분짜리라 서버를 다시 켜면 무효가 되어도 괜찮다. 그래서 키를 파일에 두지 않는다. */
    private final byte[] signingKey = new byte[32];
    /** 큰 사진은 풀면 수백 MB가 된다. 동시에 많이 풀어 메모리가 모자라지 않게 한다. */
    private final Semaphore decoding = new Semaphore(2);

    public ImageStore(MedimateProperties props) {
        this.dir = Path.of(props.storage().dir());
        this.retention = Duration.ofDays(props.storage().retentionDays());
        this.signedUrlLife = Duration.ofMinutes(props.storage().signedUrlMinutes());
        new SecureRandom().nextBytes(signingKey);
    }

    public Saved save(byte[] upload) {
        if (upload.length > MAX_BYTES) {
            throw tooLarge();
        }
        if (!ImageProcessor.hasSupportedSignature(upload)) {
            throw unsupported();
        }
        BufferedImage image;
        decoding.acquireUninterruptibly();
        try {
            image = ImageProcessor.upright(ImageProcessor.decode(upload), ImageProcessor.exifOrientation(upload));
        } catch (ImageProcessor.TooManyPixels e) {
            throw tooLarge();
        } catch (ImageProcessor.Unsupported e) {
            throw unsupported();
        } finally {
            decoding.release();
        }
        ImageQuality.check(image).ifPresent(issue -> {
            throw rejected(issue);
        });
        try {
            byte[] jpeg = ImageProcessor.encodeJpeg(image);
            String id = Ids.prefixed("f_");
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "upload", ".tmp");
            Files.write(tmp, jpeg);
            Files.move(tmp, dir.resolve(id + ".jpg"), StandardCopyOption.ATOMIC_MOVE);
            Instant now = Times.now();
            Instant signedUntil = now.plus(signedUrlLife);
            return new Saved(id, jpeg.length, image.getWidth(), image.getHeight(), signedUntil,
                    sign(id, signedUntil.getEpochSecond()), now.plus(retention));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ApiException tooLarge() {
        return ApiException.of(HttpStatus.PAYLOAD_TOO_LARGE, "MM-FIL-4130", "이미지는 10MB, 5천만 화소까지 올릴 수 있습니다.",
                "사진 용량이 너무 큽니다. 다시 찍어 주세요.");
    }

    private static ApiException unsupported() {
        return ApiException.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MM-FIL-4150", "JPEG 또는 PNG 이미지만 올릴 수 있습니다.",
                "사진 파일을 읽을 수 없습니다. 다시 찍어 주세요.");
    }

    private static ApiException rejected(ImageQuality.Issue issue) {
        return switch (issue) {
            case DARK -> ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "MM-FIL-4220", "사진이 너무 어둡습니다.",
                    "사진이 너무 어둡습니다. 밝은 곳에서 다시 찍어 주세요.");
            case BRIGHT -> ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "MM-FIL-4221", "사진이 너무 밝습니다.",
                    "사진이 너무 밝습니다. 조명을 피해 다시 찍어 주세요.");
            case BLUR -> ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "MM-FIL-4222", "사진이 흐립니다.",
                    "사진이 흐립니다. 휴대폰을 움직이지 말고 다시 찍어 주세요.");
        };
    }

    /** 있고 만료되지 않은 사진의 경로. */
    public Optional<Path> find(String fileId) {
        if (fileId == null || !FILE_ID.matcher(fileId).matches()) {
            return Optional.empty();
        }
        Path file = dir.resolve(fileId + ".jpg");
        try {
            if (!Files.isRegularFile(file) || isExpired(file)) {
                return Optional.empty();
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.of(file);
    }

    private boolean isExpired(Path file) throws IOException {
        return Files.getLastModifiedTime(file).toInstant().plus(retention).isBefore(Times.now());
    }

    /** 서명이 맞고 기한 안이면 사진 경로를 돌려준다. */
    public Path verifiedPath(String fileId, long expiresEpochSecond, String signature) {
        boolean signedOk = signature != null && fileId != null && MessageDigest.isEqual(
                sign(fileId, expiresEpochSecond).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
        if (!signedOk || Times.now().getEpochSecond() > expiresEpochSecond) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "MM-FIL-4030", "링크가 만료되었거나 올바르지 않습니다.",
                    "사진을 볼 수 없습니다. 다시 시도해 주세요.");
        }
        return find(fileId).orElseThrow(() -> ApiException.notFound("사진"));
    }

    private String sign(String fileId, long expiresEpochSecond) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            byte[] digest = mac.doFinal((fileId + "." + expiresEpochSecond).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 보관 기간이 지난 사진을 지운다. 이 서버가 만든 이름의 파일만 건드린다. */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 60_000L)
    public void purgeExpired() {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> STORED_NAME.matcher(p.getFileName().toString()).matches()).forEach(p -> {
                try {
                    if (isExpired(p)) {
                        Files.deleteIfExists(p);
                    }
                } catch (IOException e) {
                    log.warn("만료 사진 삭제 실패: {}", p.getFileName());
                }
            });
        } catch (IOException e) {
            log.warn("만료 사진 정리 실패: {}", e.getClass().getSimpleName());
        }
    }
}
