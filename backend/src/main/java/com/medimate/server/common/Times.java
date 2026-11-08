package com.medimate.server.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 서비스 시간대는 한국 고정이다. API에는 +09:00으로 내보낸다. */
public final class Times {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 테스트에서 시각을 옮길 수 있도록 한 곳에서만 현재 시각을 읽는다. */
    private static volatile Clock clock = Clock.system(KST);

    private Times() {
    }

    public static void setClockForTest(Clock c) {
        clock = c == null ? Clock.system(KST) : c;
    }

    public static Instant now() {
        return clock.instant();
    }

    public static OffsetDateTime nowOffset() {
        return kst(now());
    }

    public static LocalDate today() {
        return LocalDate.ofInstant(now(), KST);
    }

    public static LocalDate dateOf(Instant i) {
        return LocalDate.ofInstant(i, KST);
    }

    public static OffsetDateTime kst(Instant i) {
        return i == null ? null : i.atZone(KST).toOffsetDateTime();
    }
}
