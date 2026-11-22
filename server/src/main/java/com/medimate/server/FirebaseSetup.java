package com.medimate.server;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;

/**
 * Firebase Admin 초기화. 서비스 계정 파일이 없으면 서버는 켜지되 로그인 관련 요청은 503을 돌려준다.
 * (파일 받는 법: server/README.md 2단계)
 */
@Component
public class FirebaseSetup {

    private static final Logger log = LoggerFactory.getLogger(FirebaseSetup.class);

    private final FirebaseAuth auth;

    public FirebaseSetup(@Value("${medimate.firebase-credentials}") String credentialsPath) {
        this.auth = init(Path.of(credentialsPath));
    }

    private static FirebaseAuth init(Path path) {
        if (!Files.isRegularFile(path)) {
            log.warn("Firebase 서비스 계정 파일이 없다: {}", path);
            return null;
        }
        try (InputStream in = new FileInputStream(path.toFile())) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(in))
                    .build();
            FirebaseApp app = FirebaseApp.getApps().isEmpty()
                    ? FirebaseApp.initializeApp(options)
                    : FirebaseApp.getInstance();
            log.info("Firebase 연결됨");
            return FirebaseAuth.getInstance(app);
        } catch (IOException | RuntimeException e) {
            log.error("Firebase 초기화 실패: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    public boolean isReady() {
        return auth != null;
    }

    private FirebaseAuth auth() {
        if (auth == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "firebase not configured");
        }
        return auth;
    }

    /** "Bearer {Firebase ID 토큰}" 헤더를 확인하고 uid를 돌려준다 */
    public String verifyBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "missing token");
        }
        try {
            return auth().verifyIdToken(authorization.substring("Bearer ".length()).trim()).getUid();
        } catch (FirebaseAuthException | IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid token");
        }
    }
}
