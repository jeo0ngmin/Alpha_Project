package com.medimate.medication;

import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/** 선택한 날짜의 복용 회차 하나. 목록의 알약 버튼 한 개에 해당한다. */
public final class DoseItem {

    public enum Status {
        SCHEDULED("복용 예정"),
        /** 시간이 지났지만 기록이 없음. 먹지 않았다고 단정하지 않는다 */
        NEEDS_CHECK("복용 확인 필요"),
        TAKEN("복용 완료");

        public final String label;

        Status(String label) {
            this.label = label;
        }
    }

    public final String occurrenceKey;
    public final String scheduleId;
    public final String seriesId;
    public final String slotId;
    public final LocalDate date;
    public final LocalTime time;
    public final String drugName;
    public final String dosage;
    public final Status status;
    @Nullable
    public final Long takenAt;
    public final int syncState;
    public final boolean remindersEnabled;

    public DoseItem(String occurrenceKey, String scheduleId, String seriesId, String slotId, LocalDate date,
                    LocalTime time, String drugName, String dosage, Status status, @Nullable Long takenAt,
                    int syncState, boolean remindersEnabled) {
        this.occurrenceKey = occurrenceKey;
        this.scheduleId = scheduleId;
        this.seriesId = seriesId;
        this.slotId = slotId;
        this.date = date;
        this.time = time;
        this.drugName = drugName;
        this.dosage = dosage;
        this.status = status;
        this.takenAt = takenAt;
        this.syncState = syncState;
        this.remindersEnabled = remindersEnabled;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DoseItem)) {
            return false;
        }
        DoseItem other = (DoseItem) o;
        return occurrenceKey.equals(other.occurrenceKey)
                && scheduleId.equals(other.scheduleId)
                && time.equals(other.time)
                && drugName.equals(other.drugName)
                && dosage.equals(other.dosage)
                && status == other.status
                && Objects.equals(takenAt, other.takenAt)
                && syncState == other.syncState
                && remindersEnabled == other.remindersEnabled;
    }

    @Override
    public int hashCode() {
        return occurrenceKey.hashCode();
    }
}
