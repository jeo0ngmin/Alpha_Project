package com.medimate.server;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 설치 확인용. 키 값은 내보내지 않고 설정 여부만 알려 준다. */
@RestController
public class HealthController {

    private final FirebaseSetup firebase;
    private final ClaudeProxyController claude;

    public HealthController(FirebaseSetup firebase, ClaudeProxyController claude) {
        this.firebase = firebase;
        this.claude = claude;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "firebase", firebase.isReady(),
                "claudeKey", claude.hasKey());
    }
}
