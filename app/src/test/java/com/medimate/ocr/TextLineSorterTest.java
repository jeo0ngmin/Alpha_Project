package com.medimate.ocr;

import static org.junit.Assert.assertEquals;

import com.medimate.ocr.TextLineSorter.Item;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class TextLineSorterTest {

    @Test
    public void emptyGivesEmptyString() {
        assertEquals("", TextLineSorter.sort(Collections.emptyList()));
    }

    /** 표 형식 약봉투: 열 단위로 뒤섞여 들어온 조각을 행 → 열 순으로 되돌린다 */
    @Test
    public void sortsByRowThenColumn() {
        String sorted = TextLineSorter.sort(Arrays.asList(
                new Item("3회", 212, 700),
                new Item("타이레놀정", 100, 50),
                new Item("코대원정", 205, 50),
                new Item("1정", 110, 400),
                new Item("3회", 95, 700),
                new Item("1정", 200, 400)));

        assertEquals("타이레놀정\n1정\n3회\n코대원정\n1정\n3회", sorted);
    }

    /** 위쪽 좌표 차이가 40px 이상이면 다른 행이다 */
    @Test
    public void splitsRowsAtThreshold() {
        String sorted = TextLineSorter.sort(Arrays.asList(
                new Item("아래 왼쪽", 140, 10),
                new Item("위 오른쪽", 100, 500),
                new Item("같은 행 왼쪽", 139, 10)));

        assertEquals("같은 행 왼쪽\n위 오른쪽\n아래 왼쪽", sorted);
    }
}
