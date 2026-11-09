package com.medimate.server.common;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 고정 구간 속도 제한. 메모리에 두므로 서버를 다시 켜면 초기화된다. */
@Component
public class Limits {

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    private record Window(Instant start, int count) {
    }

    public void rate(String key, int limit, Duration window) {
        Instant now = Times.now();
        Window w = windows.compute(key, (k, cur) -> cur == null || now.isAfter(cur.start.plus(window))
                ? new Window(now, 1) : new Window(cur.start, cur.count + 1));
        if (w.count > limit) {
            long retry = Duration.between(now, w.start.plus(window)).toSeconds();
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "MM-COM-4290", "요청이 너무 많습니다.",
                    "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.", true, null, (int) Math.max(1, retry));
        }
        if (windows.size() > 50_000) {
            windows.entrySet().removeIf(e -> now.isAfter(e.getValue().start.plus(Duration.ofDays(1))));
        }
    }

    /** 테스트 간 상태 초기화용. */
    public void resetForTest() {
        windows.clear();
    }
}
