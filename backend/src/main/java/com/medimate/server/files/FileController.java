package com.medimate.server.files;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.medimate.server.common.Api;
import com.medimate.server.common.ApiException;
import com.medimate.server.common.Enums.FilePurpose;
import com.medimate.server.common.FirebaseAuthFilter;
import com.medimate.server.common.Limits;
import com.medimate.server.common.MedimateProperties;
import com.medimate.server.common.Times;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public class FileController {

    public record UploadedFile(String fileId, FilePurpose purpose, String mimeType, long sizeBytes, int width, int height,
                               URI signedUrl, OffsetDateTime signedUrlExpiresAt, OffsetDateTime expiresAt) {
    }

    private final ImageStore store;
    private final Limits limits;
    private final MedimateProperties props;

    public FileController(ImageStore store, Limits limits, MedimateProperties props) {
        this.store = store;
        this.limits = limits;
        this.props = props;
    }

    @PostMapping("/files/images")
    ResponseEntity<Api.Response<UploadedFile>> upload(@RequestParam MultipartFile file, @RequestParam FilePurpose purpose,
                                                      HttpServletRequest request) throws IOException {
        limits.rate("upload:" + FirebaseAuthFilter.clientKey(request), props.limits().uploadsPerHour(), Duration.ofHours(1));
        if (file.isEmpty()) {
            throw ApiException.badRequest("file", "빈 파일입니다.");
        }
        if (file.getSize() > ImageStore.MAX_BYTES) {
            throw ApiException.of(HttpStatus.PAYLOAD_TOO_LARGE, "MM-FIL-4130", "이미지는 10MB까지 올릴 수 있습니다.",
                    "사진 용량이 너무 큽니다. 다시 찍어 주세요.");
        }
        ImageStore.Saved s = store.save(file.getBytes());
        URI signed = ServletUriComponentsBuilder.fromCurrentContextPath().path("/files/images/{id}/content")
                .queryParam("exp", s.signedUrlExpiresAt().getEpochSecond()).queryParam("sig", s.signature())
                .buildAndExpand(s.fileId()).toUri();
        return ResponseEntity.status(HttpStatus.CREATED).body(Api.ok(new UploadedFile(s.fileId(), purpose, "image/jpeg",
                s.sizeBytes(), s.width(), s.height(), signed, Times.kst(s.signedUrlExpiresAt()), Times.kst(s.expiresAt()))));
    }

    /** 서명 URL로 사진을 내려받는다. 로그인 없이 링크만으로 열리므로 서명과 기한을 반드시 확인한다. */
    @GetMapping("/files/images/{fileId}/content")
    ResponseEntity<Resource> content(@PathVariable String fileId, @RequestParam long exp, @RequestParam String sig) {
        Path path = store.verifiedPath(fileId, exp, sig);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(path));
    }
}
