package com.medimate.ui.drug;

import static org.junit.Assert.assertEquals;

import com.medimate.drug.model.DrugSection;
import com.medimate.drug.model.MedicationInfo;
import com.medimate.model.Drug;

import org.junit.Test;

import java.util.ArrayList;

public class MedicationDrugMapperTest {

    private static DrugSection section(String key, String text) {
        DrugSection s = new DrugSection();
        s.key = key;
        s.text = text;
        return s;
    }

    private static MedicationInfo info() {
        MedicationInfo info = new MedicationInfo();
        info.itemSeq = "201902046";
        info.itemName = "씨트리카정";
        info.entpName = "제약사";
        info.sections = new ArrayList<>();
        info.sections.add(section(DrugSection.DESCRIPTION, "효능 설명"));
        info.sections.add(section(DrugSection.CAUTION, "주의사항 내용"));
        info.sections.add(section(DrugSection.USE_METHOD, "1일 1회"));
        info.sections.add(section(DrugSection.STORAGE, "실온 보관"));
        return info;
    }

    @Test
    public void usesOfficialSectionsWhenUserHasNoValues() {
        Drug drug = MedicationDrugMapper.toDrug(info(), null, "  ");
        assertEquals("씨트리카정", drug.getName());
        assertEquals("201902046", drug.getItemSeq());
        assertEquals("1일 1회", drug.getDosage());
        assertEquals("실온 보관", drug.getStorage());
        assertEquals("효능 설명", drug.getDescription());
        assertEquals("주의사항 내용", drug.getWarning());
    }

    @Test
    public void prefersUserDosageAndStorage() {
        Drug drug = MedicationDrugMapper.toDrug(info(), "아침 저녁 1정", "냉장");
        assertEquals("아침 저녁 1정", drug.getDosage());
        assertEquals("냉장", drug.getStorage());
    }

    @Test
    public void keepsFullCautionWhenServerPickedKeyPoints() {
        MedicationInfo info = info();
        info.section(DrugSection.CAUTION).fullText = "1. 경고 전체\n2. 이상반응";
        Drug drug = MedicationDrugMapper.toDrug(info, null, null);
        assertEquals("주의사항 내용", drug.getWarning());
        assertEquals("1. 경고 전체\n2. 이상반응", drug.getWarningFull());
    }

    @Test
    public void missingDescriptionSaysNoOfficialInfo() {
        MedicationInfo info = info();
        info.sections.clear();
        Drug drug = MedicationDrugMapper.toDrug(info, null, null);
        assertEquals(Drug.NO_OFFICIAL_INFO, drug.getDescription());
        assertEquals("", drug.getWarning());
    }
}
