package com.medimate.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.medimate.R;

/** 큰 글자·세로로 쌓은 큰 버튼의 대화상자. 첫 번째로 추가한 버튼이 채운 버튼(권장 행동)이다. */
public final class LargeDialog {

    private final Context context;
    private final View view;
    private final LinearLayout buttons;
    private final MaterialAlertDialogBuilder builder;
    private AlertDialog dialog;

    @SuppressLint("InflateParams") // 대화상자 뷰는 붙일 부모가 없다 (AlertDialog가 붙인다)
    public LargeDialog(Context context, CharSequence title, CharSequence message) {
        this.context = context;
        view = LayoutInflater.from(context).inflate(R.layout.dialog_large, null);
        ((TextView) view.findViewById(R.id.tvDialogTitle)).setText(title);
        ((TextView) view.findViewById(R.id.tvDialogMessage)).setText(message);
        buttons = view.findViewById(R.id.dialogButtons);
        builder = new MaterialAlertDialogBuilder(context).setView(view);
    }

    public LargeDialog button(int textRes, Runnable onClick) {
        // 스타일은 코드로 줄 수 없어서(ContextThemeWrapper는 테마만 바꾼다) 레이아웃으로 만든다
        int layout = buttons.getChildCount() == 0 ? R.layout.dialog_button_primary
                : R.layout.dialog_button_secondary;
        MaterialButton b = (MaterialButton) LayoutInflater.from(context).inflate(layout, buttons, false);
        b.setText(textRes);
        b.setOnClickListener(v -> {
            dialog.dismiss();
            if (onClick != null) {
                onClick.run();
            }
        });
        buttons.addView(b);
        return this;
    }

    public LargeDialog cancelable(boolean cancelable) {
        builder.setCancelable(cancelable);
        return this;
    }

    public AlertDialog show() {
        dialog = builder.show();
        return dialog;
    }
}
