package com.medimate.medication;

import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/** 복용 회차 계산. 미래 회차를 미리 만들어 저장하지 않고, 필요할 때 일정에서 계산한다. */
public final class Occurrences {

    /** 다음 회차를 찾을 때 볼 날짜 수. 어떤 요일 설정이든 일주일 안에 있고, 미리 체크한 회차를 건너뛸 여유를 둔다 */
    static final int LOOKAHEAD_DAYS = 15;

    private Occurrences() {
    }

    /**
     * 같은 회차는 항상 같은 키. 일정 버전이 바뀌어도 seriesId·slotId가 유지되므로,
     * 오늘 체크한 뒤 일정을 고쳐도 오늘 회차가 하나 더 생기지 않는다.
     */
    public static String key(String seriesId, String slotId, LocalDate date) {
        return seriesId + "_" + slotId + "_" + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    public static final class Next {
        public final LocalDate date;
        public final ZonedDateTime at;

        Next(LocalDate date, ZonedDateTime at) {
            this.date = date;
            this.at = at;
        }
    }

    /**
     * 지금 이후 가장 가까운 회차. 이미 복용 완료한 회차는 건너뛴다.
     * 지금보다 지난 회차는 돌려주지 않으므로, 재부팅 뒤에도 지난 알림이 한꺼번에 울리지 않는다.
     *
     * @return 남은 회차가 없으면 null
     */
    @Nullable
    public static Next next(MedicationSchedule schedule, TimeSlot slot, ZonedDateTime now, Set<String> takenKeys) {
        LocalDate date = now.toLocalDate();
        if (schedule.start().isAfter(date)) {
            date = schedule.start();
        }
        for (int i = 0; i < LOOKAHEAD_DAYS; i++, date = date.plusDays(1)) {
            if (!schedule.runsOn(date)) {
                continue;
            }
            ZonedDateTime at = ZonedDateTime.of(date, slot.localTime(), now.getZone());
            if (at.isAfter(now) && !takenKeys.contains(key(schedule.seriesId, slot.slotId, date))) {
                return new Next(date, at);
            }
        }
        return null;
    }
}
