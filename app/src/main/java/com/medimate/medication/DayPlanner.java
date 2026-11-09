package com.medimate.medication;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 일정과 복용 기록을 합쳐 날짜별 회차 목록을 만든다.
 * 복용 완료 기록은 기록에 복사해 둔 이름·복용량·시각을 쓰므로 일정을 고치거나 지워도 그대로 보인다.
 */
public final class DayPlanner {

    private DayPlanner() {
    }

    public static List<DoseItem> plan(LocalDate date, List<MedicationSchedule> schedules,
                                      List<IntakeRecord> intakes, LocalDateTime now) {
        String dateText = date.toString();
        Map<String, IntakeRecord> records = new HashMap<>();
        for (IntakeRecord record : intakes) {
            if (dateText.equals(record.scheduledDate)) {
                records.put(record.occurrenceKey, record);
            }
        }

        Map<String, DoseItem> items = new LinkedHashMap<>();
        for (MedicationSchedule schedule : schedules) {
            if (!schedule.runsOn(date)) {
                continue;
            }
            for (TimeSlot slot : schedule.slots) {
                String key = Occurrences.key(schedule.seriesId, slot.slotId, date);
                IntakeRecord record = records.get(key);
                items.put(key, record != null && record.isTaken()
                        ? fromRecord(record, schedule.remindersEnabled)
                        : fromSchedule(schedule, slot, date, record, now));
            }
        }
        for (IntakeRecord record : records.values()) {
            // 일정이 끝났거나 지워졌어도 복용 완료 기록은 보여 준다
            if (record.isTaken() && !items.containsKey(record.occurrenceKey)) {
                items.put(record.occurrenceKey, fromRecord(record, false));
            }
        }

        List<DoseItem> sorted = new ArrayList<>(items.values());
        sorted.sort(Comparator.comparing((DoseItem d) -> d.time).thenComparing(d -> d.drugName));
        return sorted;
    }

    /** 달력에 점을 찍을 날짜들 */
    public static Set<LocalDate> datesWithDoses(List<LocalDate> dates, List<MedicationSchedule> schedules,
                                                List<IntakeRecord> intakes) {
        Set<LocalDate> result = new HashSet<>();
        for (LocalDate date : dates) {
            for (MedicationSchedule schedule : schedules) {
                if (schedule.runsOn(date) && !schedule.slots.isEmpty()) {
                    result.add(date);
                    break;
                }
            }
        }
        for (IntakeRecord record : intakes) {
            if (record.isTaken()) {
                result.add(LocalDate.parse(record.scheduledDate));
            }
        }
        return result;
    }

    public static int takenCount(List<DoseItem> doses) {
        int count = 0;
        for (DoseItem dose : doses) {
            if (dose.status == DoseItem.Status.TAKEN) {
                count++;
            }
        }
        return count;
    }

    /** 일정의 한 회차를 만든다. 알림의 "복용 완료"처럼 목록 없이 회차를 기록할 때도 쓴다 */
    public static DoseItem fromSchedule(MedicationSchedule schedule, TimeSlot slot, LocalDate date,
                                        IntakeRecord record, LocalDateTime now) {
        LocalTime time = slot.localTime();
        DoseItem.Status status = now.isBefore(date.atTime(time))
                ? DoseItem.Status.SCHEDULED
                : DoseItem.Status.NEEDS_CHECK;
        return new DoseItem(Occurrences.key(schedule.seriesId, slot.slotId, date), schedule.id, schedule.seriesId,
                slot.slotId, date, time, schedule.drugName, schedule.dosage, status, null,
                record == null ? SyncState.SYNCED : record.syncState, schedule.remindersEnabled);
    }

    private static DoseItem fromRecord(IntakeRecord record, boolean remindersEnabled) {
        return new DoseItem(record.occurrenceKey, record.scheduleId, record.seriesId, record.slotId,
                LocalDate.parse(record.scheduledDate), LocalTime.parse(record.scheduledTime),
                record.drugName, record.dosage, DoseItem.Status.TAKEN, record.takenAt,
                record.syncState, remindersEnabled);
    }
}
