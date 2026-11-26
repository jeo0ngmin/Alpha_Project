package com.medimate.server.drug;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 허가정보 "사용상의 주의사항"은 수천~수만 자라 그대로 보여 주거나 읽어 주기 어렵다.
 * 꼭 알아야 할 항목(경고, 투여·복용하지 말 것, 고령자)만 골라 앞에 보여 준다.
 * 글은 바꾸거나 새로 쓰지 않고 원문 문단을 고르기만 한다 (README 5-5). 전체는 따로 보여 준다.
 */
public final class PrecautionDigest {

    /** "1. 경고", "2. 다음 환자(경우)에는 투여하지 말 것." 같은 항목 제목 줄 */
    private static final Pattern ARTICLE_TITLE = Pattern.compile("^\\d{1,2}\\.\\s*\\S.*");
    /** 고를 항목: 경고, 금기(투여·복용·사용하지 말 것), 고령자 */
    private static final Pattern KEY_ARTICLE = Pattern.compile("경고|하지\\s*말\\s*것|고령자");
    /** 항목 안의 요점 문단: "1) …", "가. …", "(1) …" */
    private static final Pattern POINT = Pattern.compile("^(\\d{1,2}\\)|[가-하]\\.|\\(\\d{1,2}\\)).*");
    /** 요점 표시가 없는 항목은 앞의 몇 문단만 쓴다 */
    private static final int PLAIN_PARAGRAPHS = 2;

    private PrecautionDigest() {
    }

    /**
     * 중요한 항목만 고른 글. 항목 제목을 찾지 못했거나 고를 것이 없으면 null (그때는 전체를 쓴다).
     * text는 MfdsPermitClient가 만든 "제목 줄 + 문단 줄" 형식이다.
     */
    public static String keyPoints(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        List<List<String>> articles = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (ARTICLE_TITLE.matcher(line).matches() || articles.isEmpty()) {
                articles.add(new ArrayList<>());
            }
            articles.get(articles.size() - 1).add(line);
        }
        List<String> out = new ArrayList<>();
        for (List<String> article : articles) {
            String title = article.get(0);
            if (!ARTICLE_TITLE.matcher(title).matches() || !KEY_ARTICLE.matcher(title).find()) {
                continue;
            }
            List<String> body = article.subList(1, article.size());
            List<String> points = new ArrayList<>();
            for (String p : body) {
                if (POINT.matcher(p).matches()) {
                    points.add(p);
                }
            }
            if (points.isEmpty()) {
                points = body.subList(0, Math.min(PLAIN_PARAGRAPHS, body.size()));
            }
            out.add(title);
            out.addAll(points);
        }
        if (out.isEmpty()) {
            return null;
        }
        String digest = String.join("\n", out);
        // 고른 결과가 전체와 거의 같으면 나눌 필요가 없다
        return digest.length() >= text.length() * 0.9 ? null : digest;
    }
}
