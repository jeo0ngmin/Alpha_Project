package com.medimate.plan.model;

import java.util.Collections;
import java.util.List;

/**
 * 복약 일정 초안 (약봉투 확정 결과의 plans). 서버는 저장하지 않는다.
 * 앱이 이것을 Firestore 일정으로 바꿔 저장하고 알람을 만든다.
 *
 * <pre>
 * itemSeq / customName  식약처 품목기준코드. 연결되지 않은 약이면 null이고 customName에 이름
 * dosageAmount + dosageUnit  1회 복용량 (TABLET | CAPSULE | PACK | ML | DROP | PUFF | UNIT)
 * timeSlots             복용 시점과 알림 시각 [{slot: MORNING, time: "08:00"}, …]
 * startDate ~ endDate   yyyy-MM-dd, 둘 다 포함
 * mealRelation          BEFORE_MEAL | AFTER_MEAL | WITH_MEAL | EMPTY_STOMACH | ANYTIME (+ mealOffsetMinutes)
 * memo                  봉투의 주의사항과 조제 약국 (알림에 함께 보여 줄 문장)
 * </pre>
 */
public class PlanDraft {
    public String itemSeq;
    public String customName;
    public Double dosageAmount;
    public String dosageUnit;
    /** DAILY (약봉투는 항상 매일) */
    public String frequencyType;
    public Integer frequencyPerDay;
    public List<TimeSlotEntry> timeSlots;
    public String mealRelation;
    public Integer mealOffsetMinutes;
    public String startDate;
    public String endDate;
    public Integer durationDays;
    /** 전체 알약 수 (1회량 × 횟수 × 일수) */
    public Double remainingQuantity;
    /** 기본 true. 사용자가 "알람으로 설정할까요?"에 아니요라고 하면 false로 저장한다. */
    public boolean notificationEnabled;
    public String memo;

    public List<TimeSlotEntry> timeSlotsOrEmpty() {
        return timeSlots == null ? Collections.emptyList() : timeSlots;
    }
}
