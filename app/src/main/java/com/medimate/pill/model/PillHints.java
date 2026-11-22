package com.medimate.pill.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** 사용자가 직접 입력한 모양·색·각인. 서버는 사진 추정보다 이 값을 우선한다. */
public class PillHints implements Serializable {
    public DrugShape drugShape;
    public ArrayList<ColorClass> colorClass;
    public String printText;

    public PillHints(DrugShape drugShape, List<ColorClass> colorClass, String printText) {
        this.drugShape = drugShape;
        this.colorClass = colorClass == null || colorClass.isEmpty() ? null : new ArrayList<>(colorClass);
        this.printText = printText == null || printText.trim().isEmpty() ? null : printText.trim();
    }

    public boolean isEmpty() {
        return drugShape == null && colorClass == null && printText == null;
    }
}
