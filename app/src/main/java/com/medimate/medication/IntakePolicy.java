package com.medimate.medication;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 복용 완료를 지금 기록해도 되는지 판단한다. 화면과 알림이 같은 규칙을 쓴다. */
public final class IntakePolicy {

    public enum Decision {
        ALLOWED,
        /** 오늘이지만 예정 시각 전. 사용자 확인을 거친다 */
        NEEDS_EARLY_CONFIRM,
        /** 내일 이후. 기록할 수 없다 */
        FUTURE_BLOCKED
    }

    private IntakePolicy() {
    }

    public static Decision decide(LocalDate date, LocalTime time, LocalDateTime now) {
        if (date.isAfter(now.toLocalDate())) {
            return Decision.FUTURE_BLOCKED;
        }
        return now.isBefore(date.atTime(time)) ? Decision.NEEDS_EARLY_CONFIRM : Decision.ALLOWED;
    }
}
