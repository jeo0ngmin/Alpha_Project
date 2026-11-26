package com.medimate.ui.drug;

import com.medimate.drug.model.DrugSection;
import com.medimate.drug.model.MedicationInfo;
import com.medimate.model.Drug;

/**
 * 서버 약 정보(GET /medications/{itemSeq}: 품목 + e약은요 sections) → 약 상세 화면의 Drug.
 * 알약 식별 결과와 내 약 목록이 같이 쓴다. 설명·주의사항은 e약은요 원문만 쓴다 (README 5-5).
 */
public final class MedicationDrugMapper {

    private MedicationDrugMapper() {
    }

    /**
     * dosage·storage: 사용자가 가진 값(봉투에서 읽은 복용법 등)이 있으면 그것을 먼저 쓰고,
     * 없으면 e약은요 복용방법·보관방법을 쓴다.
     */
    public static Drug toDrug(MedicationInfo info, String dosage, String storage) {
        String description = sectionText(info, DrugSection.DESCRIPTION);
        return new Drug(
                orEmpty(info.itemName),
                orEmpty(info.entpName),
                orEmpty(info.itemSeq),
                isBlank(dosage) ? sectionText(info, DrugSection.USE_METHOD) : dosage.trim(),
                isBlank(storage) ? sectionText(info, DrugSection.STORAGE) : storage.trim(),
                description.isEmpty() ? Drug.NO_OFFICIAL_INFO : description,
                sectionText(info, DrugSection.CAUTION),
                fullText(info, DrugSection.CAUTION));
    }

    /** 서버에서 정보를 받지 못했을 때: 가진 값만으로 만든다 */
    public static Drug withoutInfo(String name, String itemSeq, String dosage, String storage) {
        return new Drug(orEmpty(name), "", orEmpty(itemSeq), orEmpty(dosage), orEmpty(storage),
                Drug.NO_OFFICIAL_INFO, "");
    }

    private static String sectionText(MedicationInfo info, String key) {
        DrugSection section = info.section(key);
        return section != null && section.hasText() ? section.text.trim() : "";
    }

    private static String fullText(MedicationInfo info, String key) {
        DrugSection section = info.section(key);
        return section != null && section.fullText != null ? section.fullText.trim() : "";
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
