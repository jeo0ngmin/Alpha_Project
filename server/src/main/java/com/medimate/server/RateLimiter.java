package com.medimate.server;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 사용자별 한 시간 요청 수 제한 (고정 창). 서버를 다시 켜면 초기화된다. */
public class RateLimiter {

    private static final long WINDOW_MS = 60 * 60 * 1000L;

    private record Window(long start, int count) {
    }

    private final int limit;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int limit, Clock clock) {
        this.limit = limit;
        this.clock = clock;
    }

    /** 허용되면 true. 허용될 때만 횟수를 센다 */
    public boolean tryAcquire(String key) {
        long now = clock.millis();
        boolean[] allowed = {false};
        windows.compute(key, (k, w) -> {
            if (w == null || now - w.start() >= WINDOW_MS) {
                allowed[0] = true;
                return new Window(now, 1);
            }
            if (w.count() < limit) {
                allowed[0] = true;
                return new Window(w.start(), w.count() + 1);
            }
            return w;
        });
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> now - e.getValue().start() >= WINDOW_MS);
        }
        return allowed[0];
    }
}
