package com.medimate.recommend;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 추천 성분·제품의 쿠팡 검색 링크 (README 8-9). 검색 화면 주소만 만들고 API는 부르지 않는다.
 * 쿠팡을 한 번도 열지 않은 브라우저에서는 쿠팡이 검색 주소 접근을 막을 수 있다 (2026-10-30 에뮬레이터 크롬 확인).
 * 제품명은 식약처 건강기능식품 목록에서 가져오고(응답 JSON을 받은 뒤 구현), 그 전에는 성분명으로 검색한다.
 */
public final class ProductLinks {

    static final String COUPANG_SEARCH = "https://www.coupang.com/np/search?q=";

    /** 버튼 하나: 화면·TalkBack에 보이는 이름과 열 주소 */
    public static final class Link {
        public final String label;
        public final String url;

        public Link(String label, String url) {
            this.label = label;
            this.url = url;
        }
    }

    private ProductLinks() {
    }

    /** 쿠팡 검색 화면 주소 */
    public static String coupangSearchUrl(String query) {
        try {
            return COUPANG_SEARCH + URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 성분명 → 검색어. "EPA 및 DHA 함유 유지(오메가3)"처럼 괄호 안에 흔히 부르는 이름이 있으면 그것을 쓰고,
     * 식품이 아닌 영양제가 나오도록 " 영양제"를 붙인다.
     */
    public static String searchKeyword(String ingredient) {
        String name = ingredient.trim();
        int open = name.indexOf('(');
        int close = name.lastIndexOf(')');
        if (open >= 0 && close > open + 1) {
            name = name.substring(open + 1, close).trim();
        }
        return name + " 영양제";
    }

    /** 성분마다 쿠팡 검색 버튼 하나. 같은 검색어는 한 번만 */
    public static List<Link> forIngredients(List<String> ingredients) {
        Set<String> keywords = new LinkedHashSet<>();
        for (String ingredient : ingredients) {
            if (!ingredient.trim().isEmpty()) {
                keywords.add(searchKeyword(ingredient));
            }
        }
        List<Link> links = new ArrayList<>();
        for (String keyword : keywords) {
            links.add(new Link("쿠팡에서 '" + keyword + "' 찾기", coupangSearchUrl(keyword)));
        }
        return links;
    }
}
