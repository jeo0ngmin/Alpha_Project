package com.medimate.ui.scan;

import com.medimate.bag.model.BagItem;
import com.medimate.bag.model.BagJob;
import com.medimate.drug.model.DrugSection;
import com.medimate.model.Drug;

import java.util.ArrayList;
import java.util.List;

/**
 * 서버 약봉투 인식 결과(BagJob) → 약 목록 화면의 Drug.
 * 이름·용법은 봉투에서 읽은 값(CLOVA OCR), 설명·주의사항·보관법은 서버가 붙여 준 e약은요 sections.
 */
final class BagDrugMapper {

    private BagDrugMapper() {
    }

    static List<Drug> toDrugs(BagJob job) {
        List<Drug> out = new ArrayList<>();
        for (BagItem item : job.visibleItems()) {
            out.add(toDrug(item));
        }
        return out;
    }

    static Drug toDrug(BagItem item) {
        String description = sectionText(item, DrugSection.DESCRIPTION);
        String itemSeq = item.matched && item.itemSeq != null ? item.itemSeq : "";
        return new Drug(
                orEmpty(item.displayName()),
                orEmpty(item.entpName),
                itemSeq,
                item.displayText != null ? item.displayText : orEmpty(item.instructions),
                sectionText(item, DrugSection.STORAGE),
                description.isEmpty() ? Drug.NO_OFFICIAL_INFO : description,
                warning(item));
    }

    /** 봉투에 적힌 이 약의 주의사항을 먼저, 그 뒤에 e약은요 주의사항 */
    private static String warning(BagItem item) {
        StringBuilder sb = new StringBuilder();
        if (item.cautions != null) {
            for (String c : item.cautions) {
                if (c != null && !c.trim().isEmpty()) {
                    sb.append(c.trim()).append('\n');
                }
            }
        }
        sb.append(sectionText(item, DrugSection.CAUTION));
        return sb.toString().trim();
    }

    private static String sectionText(BagItem item, String key) {
        for (DrugSection s : item.sectionsOrEmpty()) {
            if (key.equals(s.key) && s.hasText()) {
                return s.text.trim();
            }
        }
        return "";
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
