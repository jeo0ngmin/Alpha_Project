package com.medimate.drug.model;

import java.util.Collections;
import java.util.List;

import com.medimate.pill.model.MedicationSummary;
import com.medimate.pill.model.PillFeatures;

/** GET /medications/{itemSeq} 응답: 품목 정보 + e약은요 설명 + 상세 화면 버튼(sections). */
public class MedicationInfo extends MedicationSummary {
    public String itemPermitDate;
    public PillFeatures appearance;
    /** e약은요에 자료가 없으면 null. */
    public DrugInfo info;
    /** FOUND | NOT_IN_MFDS | UNAVAILABLE | NOT_CONFIGURED */
    public String infoStatus;
    public List<DrugSection> sections;

    public List<DrugSection> sectionsOrEmpty() {
        return sections == null ? Collections.emptyList() : sections;
    }

    /** key(DrugSection.DESCRIPTION 등)로 항목을 찾는다. 없으면 null. */
    public DrugSection section(String key) {
        for (DrugSection s : sectionsOrEmpty()) {
            if (key.equals(s.key)) {
                return s;
            }
        }
        return null;
    }
}
