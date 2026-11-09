package com.medimate.ui.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.medimate.bag.model.BagItem;
import com.medimate.bag.model.BagJob;
import com.medimate.drug.model.DrugSection;
import com.medimate.model.Drug;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class BagDrugMapperTest {

    private static DrugSection section(String key, String text) {
        DrugSection s = new DrugSection();
        s.key = key;
        s.text = text;
        return s;
    }

    @Test
    public void matchedItemUsesMfdsNameAndSections() {
        BagItem item = new BagItem();
        item.rawText = "타이레놀정";
        item.matched = true;
        item.itemSeq = "200000001";
        item.itemName = "타이레놀정500밀리그람";
        item.entpName = "한국얀센";
        item.displayText = "1회 1정 · 하루 3회 · 식후 30분";
        item.cautions = Collections.singletonList("졸릴 수 있어요");
        item.sections = Arrays.asList(
                section(DrugSection.DESCRIPTION, "해열·진통"),
                section(DrugSection.CAUTION, "간 질환 환자는 주의"),
                section(DrugSection.STORAGE, "실온 보관"));

        Drug d = BagDrugMapper.toDrug(item);

        assertEquals("타이레놀정500밀리그람", d.getName());
        assertEquals("한국얀센", d.getEntpName());
        assertEquals("200000001", d.getItemSeq());
        assertEquals("1회 1정 · 하루 3회 · 식후 30분", d.getDosage());
        assertEquals("실온 보관", d.getStorage());
        assertEquals("해열·진통", d.getDescription());
        assertEquals("졸릴 수 있어요\n간 질환 환자는 주의", d.getWarning());
        assertTrue(d.hasOfficialInfo());
    }

    @Test
    public void unmatchedItemFallsBackToBagText() {
        BagItem item = new BagItem();
        item.rawText = "알수없는정";
        item.refinedName = "알수없는정";
        item.matched = false;
        item.itemSeq = "999";   // 연결되지 않았으면 품목코드를 쓰지 않는다
        item.instructions = "하루 2번";

        Drug d = BagDrugMapper.toDrug(item);

        assertEquals("알수없는정", d.getName());
        assertEquals("", d.getItemSeq());
        assertEquals("하루 2번", d.getDosage());
        assertEquals(Drug.NO_OFFICIAL_INFO, d.getDescription());
        assertEquals("", d.getWarning());
        assertFalse(d.hasOfficialInfo());
    }

    @Test
    public void excludedItemsAreDropped() {
        BagItem kept = new BagItem();
        kept.rawText = "A정";
        BagItem excluded = new BagItem();
        excluded.rawText = "B정";
        excluded.excluded = true;
        BagJob job = new BagJob();
        job.items = Arrays.asList(kept, excluded);

        List<Drug> drugs = BagDrugMapper.toDrugs(job);

        assertEquals(1, drugs.size());
        assertEquals("A정", drugs.get(0).getName());
    }
}
