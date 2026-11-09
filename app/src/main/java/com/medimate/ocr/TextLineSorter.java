package com.medimate.ocr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 인식된 글자 조각을 좌표 기준 Y(행) → X(열) 순으로 다시 정렬한다 (README 4-0).
 * OCR 엔진의 타입을 쓰지 않으므로 CLOVA로 바꿔도 재사용한다.
 */
public final class TextLineSorter {

    /** 위쪽 좌표 차이가 이 값보다 작으면 같은 행으로 본다. */
    static final int SAME_ROW_THRESHOLD_PX = 40;

    private TextLineSorter() {
    }

    /** 글자 조각 하나와 그 왼쪽 위 좌표(px). */
    public static final class Item {
        final String text;
        final int top;
        final int left;

        public Item(String text, int top, int left) {
            this.text = text;
            this.top = top;
            this.left = left;
        }
    }

    /** 정렬한 조각을 줄바꿈으로 이어 붙인다. 조각이 없으면 빈 문자열. */
    public static String sort(List<Item> items) {
        List<Item> byTop = new ArrayList<>(items);
        byTop.sort(Comparator.comparingInt(item -> item.top));

        StringBuilder sb = new StringBuilder();
        List<Item> row = new ArrayList<>();
        for (Item item : byTop) {
            // 행의 기준은 그 행에서 가장 위에 있는 조각이다
            if (!row.isEmpty() && (long) item.top - row.get(0).top >= SAME_ROW_THRESHOLD_PX) {
                appendRow(sb, row);
                row.clear();
            }
            row.add(item);
        }
        appendRow(sb, row);
        return sb.toString().trim();
    }

    private static void appendRow(StringBuilder sb, List<Item> row) {
        row.sort(Comparator.comparingInt(item -> item.left));
        for (Item item : row) {
            sb.append(item.text).append('\n');
        }
    }
}
