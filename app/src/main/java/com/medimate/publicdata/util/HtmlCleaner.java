package com.medimate.publicdata.util;

import androidx.core.text.HtmlCompat;

/** e약은요 응답에 섞인 <p>, <sup> 같은 HTML 태그를 제거하고 공백을 정리한다 (README 5-2). */
public final class HtmlCleaner {

    private HtmlCleaner() {
    }

    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        // fromHtml이 태그 제거와 &amp; 같은 엔티티 해제를 함께 처리한다
        String text = HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_LEGACY).toString();
        return text.replace(' ', ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{2,}", "\n")
                .trim();
    }
}
