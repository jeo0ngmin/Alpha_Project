package com.medimate.medication;

import java.time.LocalTime;
import java.util.Objects;

/** 하루 중 복용 시각 하나. slotId는 시각을 고쳐도 유지되어 같은 회차를 계속 가리킨다. */
public class TimeSlot {

    public final String slotId;
    /** "HH:mm". 기기 시간대의 벽시계 시각으로 해석한다 (README 9-6) */
    public final String time;

    public TimeSlot(String slotId, String time) {
        this.slotId = slotId;
        this.time = time;
    }

    public LocalTime localTime() {
        return LocalTime.parse(time);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TimeSlot)) {
            return false;
        }
        TimeSlot other = (TimeSlot) o;
        return Objects.equals(slotId, other.slotId) && Objects.equals(time, other.time);
    }

    @Override
    public int hashCode() {
        return Objects.hash(slotId, time);
    }
}
