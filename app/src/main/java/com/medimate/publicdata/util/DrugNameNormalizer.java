package com.medimate.publicdata.util;

import java.util.regex.Pattern;

/**
 * OCR/GPT가 뽑은 약 이름을 e약은요 조회용으로 다듬는다 (README 6-1).
 * 1차는 용량 제거만 한다. 괄호·제형 접미사 처리는 2차.
 */
public final class DrugNameNormalizer {

    // "500mg", "500 밀리그램", "0.5g", "10mL" 처럼 숫자 + 단위
    private static final Pattern DOSE = Pattern.compile(
            "\\s*\\d+(\\.\\d+)?\\s*(mg|㎎|밀리그램|밀리그람|mcg|㎍|마이크로그램|g|그램|ml|mL|㎖|밀리리터|IU)",
            Pattern.CASE_INSENSITIVE);

    private DrugNameNormalizer() {
    }

    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return DOSE.matcher(name).replaceAll("").trim();
    }
}
