package com.medimate.ui.schedule;

import androidx.lifecycle.ViewModel;

import com.medimate.medication.MedicationSchedule;
import com.medimate.medication.ScheduleDraft;

/** 등록·수정 화면의 입력값을 화면 회전에도 유지한다. */
public class ScheduleEditViewModel extends ViewModel {

    final ScheduleDraft draft = new ScheduleDraft();
    /** 수정 중인 버전. 새로 등록하면 null */
    MedicationSchedule current;
    boolean initialized;
    /** 약 목록에서 넘어온 약. 이름을 바꾸면 품목기준코드를 저장하지 않는다 */
    String sourceName = "";
    String sourceMedicationId = "";
}
