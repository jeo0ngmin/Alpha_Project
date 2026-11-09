package com.medimate.pill.model;

public class PillCandidate {
    public int rank;
    public String itemSeq;
    public String itemName;
    public String entpName;
    public String className;
    public String etcOtcName;
    public String itemImageUrl;
    public double matchScore;
    /** 식별 요청 때 보낸 currentMedications에 있는 약인지 */
    public boolean isInMyMedications;

    public int matchPercent() {
        return (int) Math.round(matchScore * 100);
    }
}
