package com.medimate.pill.model;

/** 사진에서 추정한 알약 특징. */
public class PillFeatures {
    public DrugShape drugShape;
    public ColorClass colorClass1;
    public ColorClass colorClass2;
    public String printFront;
    public String printBack;
    public String lineFront;
    public Double lengLong;
    public Double lengShort;
    public Double thick;
    public Double featureConfidence;

    /** 예: "노란색 원형 · 각인 YH" */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (colorClass1 != null) {
            sb.append(colorClass1.label);
            if (colorClass2 != null) {
                sb.append('·').append(colorClass2.label);
            }
            sb.append(' ');
        }
        if (drugShape != null) {
            sb.append(drugShape.label);
        }
        if (printFront != null && !printFront.isEmpty()) {
            sb.append(" · 각인 ").append(printFront);
        }
        return sb.toString().trim();
    }
}
