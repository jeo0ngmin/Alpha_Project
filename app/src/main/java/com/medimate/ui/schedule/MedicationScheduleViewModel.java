package com.medimate.ui.schedule;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.medimate.data.medication.MedicationRepository;
import com.medimate.medication.DayPlanner;
import com.medimate.medication.DoseItem;
import com.medimate.medication.IntakeRecord;
import com.medimate.medication.MedicationSchedule;
import com.medimate.medication.SyncState;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** 복약 스케줄 화면 상태. 달력 상태는 화면 회전에도 유지된다. */
public class MedicationScheduleViewModel extends AndroidViewModel {

    static final class ScreenState {
        final String title;
        final boolean expanded;
        final List<CalendarDayAdapter.Day> days;
        final LocalDate selected;
        final List<DoseItem> doses;
        final int takenCount;
        final boolean hasReminders;

        ScreenState(String title, boolean expanded, List<CalendarDayAdapter.Day> days, LocalDate selected,
                    List<DoseItem> doses, int takenCount, boolean hasReminders) {
            this.title = title;
            this.expanded = expanded;
            this.days = days;
            this.selected = selected;
            this.doses = doses;
            this.takenCount = takenCount;
            this.hasReminders = hasReminders;
        }
    }

    private static final class Range {
        final LocalDate from;
        final LocalDate to;

        Range(LocalDate from, LocalDate to) {
            this.from = from;
            this.to = to;
        }

        boolean contains(Range other) {
            return !other.from.isBefore(from) && !other.to.isAfter(to);
        }

        boolean sameAs(Range other) {
            return from.equals(other.from) && to.equals(other.to);
        }
    }

    private final MedicationRepository repository;
    private final String owner;
    private final MutableLiveData<Range> range = new MutableLiveData<>();
    private final MediatorLiveData<ScreenState> state = new MediatorLiveData<>();
    private final List<Range> loadedRanges = new ArrayList<>();

    private LocalDate selected = LocalDate.now();
    private YearMonth visibleMonth = YearMonth.from(selected);
    private boolean expanded;
    private List<MedicationSchedule> schedules = Collections.emptyList();
    private List<IntakeRecord> intakes = Collections.emptyList();

    public MedicationScheduleViewModel(@NonNull Application application) {
        super(application);
        repository = MedicationRepository.get(application);
        owner = repository.currentOwner();
        state.addSource(repository.observeSchedules(owner), value -> {
            schedules = value;
            publish();
        });
        state.addSource(Transformations.switchMap(range,
                r -> repository.observeIntakes(owner, r.from, r.to)), value -> {
            intakes = value;
            publish();
        });
        updateRange();
        repository.requestSync();
    }

    LiveData<ScreenState> state() {
        return state;
    }

    LiveData<Integer> pendingCount() {
        return repository.observeSyncCount(owner, SyncState.PENDING);
    }

    LiveData<Integer> failedCount() {
        return repository.observeSyncCount(owner, SyncState.FAILED);
    }

    /** 날짜를 고른다. 월간 달력이면 그 날짜가 든 주간 달력으로 접힌다 */
    void selectDate(LocalDate date) {
        selected = date;
        visibleMonth = YearMonth.from(date);
        expanded = false;
        updateRange();
    }

    void goToToday() {
        selectDate(LocalDate.now());
    }

    void toggleExpanded() {
        expanded = !expanded;
        visibleMonth = YearMonth.from(selected);
        updateRange();
    }

    void goPrevious() {
        move(-1);
    }

    void goNext() {
        move(1);
    }

    /** 월간이면 달을 넘기고(선택은 유지), 주간이면 선택한 날짜를 한 주 옮긴다 */
    private void move(int direction) {
        if (expanded) {
            visibleMonth = visibleMonth.plusMonths(direction);
        } else {
            selected = selected.plusWeeks(direction);
            visibleMonth = YearMonth.from(selected);
        }
        updateRange();
    }

    /** 시간이 지나 복용 예정이 복용 확인 필요로 바뀌도록 다시 계산한다 */
    void refreshNow() {
        publish();
    }

    private List<LocalDate> visibleDates() {
        return expanded ? CalendarRange.monthGrid(visibleMonth) : CalendarRange.week(selected);
    }

    private void updateRange() {
        List<LocalDate> dates = visibleDates();
        LocalDate first = dates.get(0);
        LocalDate last = dates.get(dates.size() - 1);
        Range next = new Range(selected.isBefore(first) ? selected : first,
                selected.isAfter(last) ? selected : last);
        loadFromServerOnce(next);
        Range current = range.getValue();
        if (current == null || !current.sameAs(next)) {
            range.setValue(next);
        }
        publish();
    }

    private void loadFromServerOnce(Range next) {
        for (Range loaded : loadedRanges) {
            if (loaded.contains(next)) {
                return;
            }
        }
        loadedRanges.add(next);
        repository.loadRange(next.from, next.to);
    }

    private void publish() {
        LocalDate today = LocalDate.now();
        List<LocalDate> dates = visibleDates();
        Set<LocalDate> doseDates = DayPlanner.datesWithDoses(dates, schedules, intakes);
        List<CalendarDayAdapter.Day> days = new ArrayList<>(dates.size());
        for (LocalDate date : dates) {
            days.add(new CalendarDayAdapter.Day(date, date.equals(today), date.equals(selected),
                    doseDates.contains(date), expanded && !YearMonth.from(date).equals(visibleMonth), !expanded));
        }
        List<DoseItem> doses = DayPlanner.plan(selected, schedules, intakes, LocalDateTime.now());
        boolean hasReminders = false;
        for (MedicationSchedule schedule : schedules) {
            LocalDate end = schedule.end();
            hasReminders |= schedule.remindersEnabled && !schedule.deleted && (end == null || !end.isBefore(today));
        }
        state.setValue(new ScreenState(DoseText.month(expanded ? visibleMonth : YearMonth.from(selected)), expanded,
                days, selected, doses, DayPlanner.takenCount(doses), hasReminders));
    }
}
