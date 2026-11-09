package com.medimate.recommend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ProductLinksTest {

    @Test
    public void keywordUsesCommonNameInParentheses() {
        assertEquals("오메가3 영양제", ProductLinks.searchKeyword("EPA 및 DHA 함유 유지(오메가3)"));
        assertEquals("마그네슘 영양제", ProductLinks.searchKeyword(" 마그네슘 "));
    }

    @Test
    public void searchUrlIsEncoded() {
        assertEquals("https://www.coupang.com/np/search?q=%EB%A3%A8%ED%85%8C%EC%9D%B8+%EC%98%81%EC%96%91%EC%A0%9C",
                ProductLinks.coupangSearchUrl("루테인 영양제"));
    }

    /** 같은 검색어는 버튼 하나, 빈 이름은 건너뛴다 */
    @Test
    public void oneButtonPerKeyword() {
        List<ProductLinks.Link> links = ProductLinks.forIngredients(
                Arrays.asList("EPA 및 DHA 함유 유지(오메가3)", "오메가3", "", "루테인"));

        assertEquals(2, links.size());
        assertEquals("쿠팡에서 '오메가3 영양제' 찾기", links.get(0).label);
        assertTrue(links.get(1).url.startsWith("https://www.coupang.com/np/search?q="));
    }
}
