package com.medimate.pill;

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.medimate.R;
import com.medimate.pill.model.ColorClass;
import com.medimate.pill.model.DrugShape;

/** 모양 선택 스피너와 색 선택 칩 (촬영 화면·검색 화면 공용). */
final class HintViews {

    /** 모양 목록. 식약처 분류에 없는 CAPSULE·OTHER도 사용자가 고를 수 있게 둔다. */
    private static final DrugShape[] SHAPES = DrugShape.values();

    private HintViews() {
    }

    static void setupShapes(Spinner spinner) {
        Context context = spinner.getContext();
        List<String> labels = new ArrayList<>();
        labels.add(context.getString(R.string.shape_unknown));
        for (DrugShape s : SHAPES) {
            labels.add(s.label);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    static DrugShape selectedShape(Spinner spinner) {
        int pos = spinner.getSelectedItemPosition();
        return pos <= 0 ? null : SHAPES[pos - 1];
    }

    static void selectShape(Spinner spinner, DrugShape shape) {
        spinner.setSelection(shape == null ? 0 : shape.ordinal() + 1);
    }

    /** maxSelected를 넘기면 마지막으로 누른 칩을 되돌리고 onLimit을 부른다. */
    static void setupColors(ChipGroup group, int maxSelected, Runnable onLimit) {
        for (ColorClass c : ColorClass.values()) {
            Chip chip = new Chip(group.getContext());
            chip.setText(c.label);
            chip.setTag(c);
            chip.setCheckable(true);
            chip.setTextSize(18);
            chip.setMinHeight(group.getResources().getDimensionPixelSize(R.dimen.touch_target));
            chip.setOnCheckedChangeListener((button, checked) -> {
                if (checked && selectedColors(group).size() > maxSelected) {
                    button.setChecked(false);
                    if (onLimit != null) {
                        onLimit.run();
                    }
                }
            });
            group.addView(chip);
        }
    }

    static List<ColorClass> selectedColors(ChipGroup group) {
        List<ColorClass> out = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            Chip chip = (Chip) group.getChildAt(i);
            if (chip.isChecked()) {
                out.add((ColorClass) chip.getTag());
            }
        }
        return out;
    }

    static void selectColors(ChipGroup group, List<ColorClass> colors) {
        for (int i = 0; i < group.getChildCount(); i++) {
            Chip chip = (Chip) group.getChildAt(i);
            chip.setChecked(colors != null && colors.contains((ColorClass) chip.getTag()));
        }
    }
}
