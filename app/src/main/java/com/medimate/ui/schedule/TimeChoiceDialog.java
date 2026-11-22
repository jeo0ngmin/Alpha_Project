package com.medimate.ui.schedule;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TimePicker;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.medimate.R;

import java.time.LocalTime;
import java.util.function.Consumer;

/**
 * 알림 시간 고르기 (README 9-6). 시계 모양 대신 위아래로 돌리는 휠(오전/오후 · 시 · 분)로 고른다.
 * 열 때 지금 값을 읽어 주고, "확인"을 누르면 고른 시간을 읽어 준다.
 */
final class TimeChoiceDialog {

    interface Picked {
        void onPicked(LocalTime time);
    }

    private TimeChoiceDialog() {
    }

    static void show(Context context, LocalTime initial, Consumer<String> speak, Picked picked) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_time_choice, null);
        TimePicker picker = view.findViewById(R.id.time_picker);
        picker.setIs24HourView(false);
        picker.setHour(initial.getHour());
        picker.setMinute(initial.getMinute());

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.time_dialog_title)
                .setView(view)
                .setPositiveButton(R.string.time_confirm, (d, w) -> {
                    LocalTime time = LocalTime.of(picker.getHour(), picker.getMinute());
                    speak.accept(context.getString(R.string.time_picked_spoken, spoken(time)));
                    picked.onPicked(time);
                })
                .setNegativeButton(R.string.time_cancel, null)
                .show();
        speak.accept(context.getString(R.string.time_dialog_spoken, spoken(initial)));
    }

    /** "오전 8시", "오후 6시 30분", "낮 12시", "밤 12시" */
    static String spoken(LocalTime time) {
        int hour = time.getHour();
        String half = hour < 12 ? "오전" : "오후";
        int h12 = hour % 12 == 0 ? 12 : hour % 12;
        if (hour == 0) {
            half = "밤";
        } else if (hour == 12) {
            half = "낮";
        }
        String text = half + " " + h12 + "시";
        return time.getMinute() == 0 ? text : text + " " + time.getMinute() + "분";
    }
}
