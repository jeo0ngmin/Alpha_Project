package com.medimate.ui.schedule;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;

import com.medimate.data.medication.MedicationRepository;
import com.medimate.medication.DayPlanner;
import com.medimate.medication.DoseItem;
import com.medimate.medication.IntakeRecord;
import com.medimate.medication.MedicationSchedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/** 홈의 "오늘 복약 진행률·오늘의 복약 일정". 로그인 사용자(Firebase)만 쓴다. */
public class TodayDosesViewModel extends AndroidViewModel {

    private final MedicationRepository repository;
    private final MediatorLiveData<List<DoseItem>> doses = new MediatorLiveData<>();
    private final LocalDate today = LocalDate.now();
    private List<MedicationSchedule> schedules = Collections.emptyList();
    private List<IntakeRecord> intakes = Collections.emptyList();

    public TodayDosesViewModel(@NonNull Application application) {
        super(application);
        repository = MedicationRepository.get(application);
        String owner = repository.currentOwner();
        if (owner == null) {
            return;
        }
        doses.addSource(repository.observeSchedules(owner), value -> {
            schedules = value;
            refreshNow();
        });
        doses.addSource(repository.observeIntakes(owner, today, today), value -> {
            intakes = value;
            refreshNow();
        });
        repository.loadRange(today, today);
        repository.requestSync();
    }

    /** 오늘 회차(시간순). 로그인하지 않았으면 값이 오지 않는다 */
    public LiveData<List<DoseItem>> doses() {
        return doses;
    }

    /** 시간이 지나 "복용 예정"이 "복용 확인 필요"로 바뀌도록 다시 계산한다 */
    public void refreshNow() {
        doses.setValue(DayPlanner.plan(today, schedules, intakes, LocalDateTime.now()));
    }

    public static int takenCount(List<DoseItem> doses) {
        return DayPlanner.takenCount(doses);
    }
}
