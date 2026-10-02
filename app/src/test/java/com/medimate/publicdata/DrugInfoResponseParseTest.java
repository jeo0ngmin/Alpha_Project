package com.medimate.publicdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.medimate.publicdata.model.DrugInfoItem;
import com.medimate.publicdata.model.DrugInfoResponse;

import org.junit.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 브라우저로 받은 실제 e약은요 응답(타이레놀, numOfRows=2)이 모델에 맞게 파싱되는지 확인 */
public class DrugInfoResponseParseTest {

    private DrugInfoResponse load() throws Exception {
        try (Reader reader = new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("publicdata/getDrbEasyDrugList.json"),
                StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, DrugInfoResponse.class);
        }
    }

    @Test
    public void parsesHeaderAndBody() throws Exception {
        DrugInfoResponse response = load();

        assertTrue(response.isSuccess());
        assertEquals("NORMAL SERVICE.", response.getHeader().getResultMsg());
        assertEquals(1, response.getBody().getPageNo());
        assertEquals(7, response.getBody().getTotalCount());
        assertEquals(2, response.getBody().getNumOfRows());
    }

    @Test
    public void parsesItems() throws Exception {
        List<DrugInfoItem> items = load().getItems();

        assertEquals(2, items.size());

        DrugInfoItem first = items.get(0);
        assertEquals("202005623", first.getItemSeq());
        assertEquals("켄뷰코리아판매유한회사", first.getEntpName());
        assertTrue(first.getEfcyQesitm().startsWith("이 약은 감기로 인한 발열"));
        assertNull(first.getItemImage());

        DrugInfoItem second = items.get(1);
        assertEquals("타이레놀정500밀리그람(아세트아미노펜)", second.getItemName());
        assertEquals("https://nedrug.mfds.go.kr/pbp/cmn/itemImageDownload/1OKRXo9l4D5", second.getItemImage());
    }

    /** 실제 0건 응답: items 필드 자체가 없다 (itemName=없는약이름123) */
    @Test
    public void noResultGivesEmptyList() {
        DrugInfoResponse response = new Gson().fromJson(
                "{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL SERVICE.\"},"
                        + "\"body\":{\"pageNo\":1,\"totalCount\":0,\"numOfRows\":2}}",
                DrugInfoResponse.class);

        assertTrue(response.isSuccess());
        assertEquals(0, response.getBody().getTotalCount());
        assertTrue(response.getItems().isEmpty());
    }
}
