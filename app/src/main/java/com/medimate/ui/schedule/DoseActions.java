package com.medimate.ui.schedule;

import android.app.Activity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.medimate.R;
import com.medimate.data.medication.MedicationRepository;
import com.medimate.medication.DoseItem;
import com.medimate.medication.IntakePolicy;
import com.medimate.medication.MedicationSchedule;
import com.medimate.tts.TtsManager;

import java.time.LocalDateTime;
import java.util.function.Consumer;

/**
 * 복용 회차 버튼을 눌렀을 때의 동작 (README 9-6). 홈과 복약알림 화면이 같이 쓴다.
 * 미래 회차는 막고, 이른 복용과 완료 취소는 한 번 더 묻는다. 안내는 say로 보여 주고 읽어 준다.
 */
public final class DoseActions {

    private static final String MSG_FUTURE = "아직 오지 않은 날짜는 복용 완료로 기록할 수 없어요.";
    private static final String MSG_NOT_FOUND = "이 복용 기록을 찾지 못했어요. 화면을 다시 열어 주세요.";
    private static final String MSG_ENDED = "이미 끝났거나 삭제한 일정이라 수정할 수 없어요.";
    private static final String MSG_DELETED = "일정을 삭제했어요. 지난 복용 기록은 그대로 남아 있어요.";
    private static final String MSG_CANCELLED = "복용 완료를 취소했어요.";

    /** 일정 수정 화면을 여는 쪽(화면)이 정한다 */
    public interface EditOpener {
        void open(MedicationSchedule schedule);
    }

    private final Activity activity;
    private final MedicationRepository repository;
    private final TtsManager tts;
    private final Consumer<String> say;

    public DoseActions(Activity activity, MedicationRepository repository, TtsManager tts, Consumer<String> say) {
        this.activity = activity;
        this.repository = repository;
        this.tts = tts;
        this.say = say;
    }

    /** 회차를 눌렀다: 완료면 취소를 묻고, 아니면 복용 완료로 기록한다 */
    public void onTap(DoseItem dose) {
        if (dose.status == DoseItem.Status.TAKEN) {
            confirmCancel(dose);
            return;
        }
        IntakePolicy.Decision decision = IntakePolicy.decide(dose.date, dose.time, LocalDateTime.now());
        if (decision == IntakePolicy.Decision.FUTURE_BLOCKED) {
            say.accept(MSG_FUTURE);
        } else if (decision == IntakePolicy.Decision.NEEDS_EARLY_CONFIRM) {
            confirmEarly(dose);
        } else {
            mark(dose, false);
        }
    }

    /** 더보기: 일정 수정·삭제 */
    public void onMore(DoseItem dose, EditOpener editOpener) {
        repository.findEditable(dose.seriesId, schedule -> {
            if (schedule == null) {
                say.accept(MSG_ENDED);
                return;
            }
            new MaterialAlertDialogBuilder(activity)
                    .setTitle(schedule.drugName)
                    .setItems(new CharSequence[]{activity.getString(R.string.schedule_menu_edit),
                            activity.getString(R.string.schedule_menu_delete)}, (d, which) -> {
                        if (which == 0) {
                            editOpener.open(schedule);
                        } else {
                            confirmDelete(schedule);
                        }
                    })
                    .show();
        });
    }

    private void mark(DoseItem dose, boolean allowEarly) {
        repository.markTaken(dose, allowEarly, result -> {
            switch (result) {
                case RECORDED:
                    say.accept(activity.getString(R.string.schedule_taken_recorded, dose.drugName));
                    break;
                case NEEDS_EARLY_CONFIRM:
                    confirmEarly(dose);
                    break;
                case FUTURE_BLOCKED:
                    say.accept(MSG_FUTURE);
                    break;
                case NOT_FOUND:
                    say.accept(MSG_NOT_FOUND);
                    break;
                default:
                    // 이미 완료된 회차: 기록은 그대로이고 화면이 곧 갱신된다
                    break;
            }
        });
    }

    private void confirmEarly(DoseItem dose) {
        String message = activity.getString(R.string.schedule_early_message, DoseText.time(dose.time), dose.drugName);
        tts.speak(message);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.schedule_early_title)
                .setMessage(message)
                .setPositiveButton(R.string.schedule_early_confirm, (d, w) -> mark(dose, true))
                .setNegativeButton(R.string.schedule_dialog_keep, null)
                .show();
    }

    private void confirmCancel(DoseItem dose) {
        String message = activity.getString(R.string.schedule_cancel_message, dose.drugName, DoseText.time(dose.time));
        tts.speak(message);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.schedule_cancel_title)
                .setMessage(message)
                .setPositiveButton(R.string.schedule_cancel_confirm, (d, w) ->
                        repository.cancelTaken(dose, cancelled -> say.accept(MSG_CANCELLED)))
                .setNegativeButton(R.string.schedule_dialog_keep, null)
                .show();
    }

    private void confirmDelete(MedicationSchedule schedule) {
        String message = activity.getString(R.string.schedule_delete_message, schedule.drugName);
        tts.speak(message);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.schedule_delete_title)
                .setMessage(message)
                .setPositiveButton(R.string.schedule_menu_delete, (d, w) ->
                        repository.delete(schedule, done -> say.accept(MSG_DELETED)))
                .setNegativeButton(R.string.schedule_dialog_keep, null)
                .show();
    }
}
