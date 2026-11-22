package com.medimate.pill.model;

public enum DrugShape {
    ROUND("원형"), OVAL("타원형"), OBLONG("장방형"), TRIANGLE("삼각형"), SQUARE("사각형"),
    DIAMOND("마름모형"), PENTAGON("오각형"), HEXAGON("육각형"), OCTAGON("팔각형"),
    CAPSULE("캡슐형"), OTHER("기타");

    public final String label;

    DrugShape(String label) {
        this.label = label;
    }
}
