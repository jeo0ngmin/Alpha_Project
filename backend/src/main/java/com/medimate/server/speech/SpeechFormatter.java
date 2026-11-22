package com.medimate.server.speech;

import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * 음성 안내 문장 재료. 약 이름을 그대로 읽게 두지 않고 낭독하기 좋게 바꾼다.
 * "500mg"를 기기 TTS가 틀리게 읽지 않도록 숫자·단위를 한글로 풀어 쓴다.
 */
public final class SpeechFormatter {

    private static final String[] DIGITS = {"", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구"};
    private static final String[] SMALL_UNITS = {"", "십", "백", "천"};
    private static final String[] BIG_UNITS = {"", "만", "억"};
    private static final String[] NATIVE = {"", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열",
            "열한", "열두"};

    /** 숫자 + 단위. 긴 단위를 먼저 둬서 "밀리리터"가 "밀리"로 잘리지 않게 한다. 단위 뒤의 조사("500mg을", "1정씩")는 허용한다. */
    private static final Pattern AMOUNT = Pattern.compile(
            "(\\d[\\d,]*(?:\\.\\d+)?)\\s*(마이크로그램|밀리그램|밀리그람|밀리리터|킬로그램|mcg|µg|μg|㎍|mg|㎎|mL|ml|㎖|kg|IU|g|L|%|퍼센트|정|알|캡슐|포|회|번|시간|일|분|세|개월|주)(?![A-Za-z])");
    private static final Pattern PER_DAY = Pattern.compile("1\\s*일\\s*(\\d+)\\s*회");
    private static final Pattern PER_TAKE = Pattern.compile("1\\s*회\\s*(\\d+(?:\\.\\d+)?)\\s*(정|알|캡슐|포)");
    /** "1회 1~2정"처럼 범위로 적힌 1회량, "1~2시간"처럼 범위로 적힌 양. */
    private static final Pattern PER_TAKE_RANGE = Pattern.compile(
            "1\\s*회\\s*(\\d+(?:\\.\\d+)?)\\s*[~∼～]\\s*(\\d+(?:\\.\\d+)?)\\s*(정|알|캡슐|포)");
    private static final Pattern RANGE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*[~∼～]\\s*(\\d+(?:\\.\\d+)?)\\s*(마이크로그램|밀리그램|밀리그람|밀리리터|mcg|mg|mL|ml|g|정|알|캡슐|포|회|번|시간|일|분|주|개월)(?![A-Za-z])");
    private static final Pattern BARE_NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    private SpeechFormatter() {
    }

    /** 한자어 수사: 500 → 오백, 2.5 → 이 점 오, 10000 → 만. */
    public static String sino(String number) {
        String clean = number.replace(",", "");
        int dot = clean.indexOf('.');
        if (dot >= 0) {
            StringBuilder fraction = new StringBuilder();
            for (char c : clean.substring(dot + 1).toCharArray()) {
                fraction.append(c == '0' ? "영" : DIGITS[c - '0']);
                fraction.append(' ');
            }
            String whole = clean.substring(0, dot);
            return (whole.isEmpty() || whole.equals("0") ? "영" : sino(whole)) + " 점 " + fraction.toString().trim();
        }
        long value;
        try {
            value = Long.parseLong(clean);
        } catch (NumberFormatException e) {
            return digitsOneByOne(clean);
        }
        if (value == 0) {
            return "영";
        }
        if (value >= 1_000_000_000_000L) {
            return digitsOneByOne(clean);
        }
        StringBuilder out = new StringBuilder();
        int group = 0;
        while (value > 0) {
            int chunk = (int) (value % 10000);
            if (chunk > 0) {
                String text = chunkToKorean(chunk);
                // 10000은 "일만"이 아니라 "만"으로 읽는다
                if (group == 1 && chunk == 1) {
                    text = "";
                }
                out.insert(0, text + BIG_UNITS[group]);
            }
            value /= 10000;
            group++;
        }
        return out.toString();
    }

    public static String sino(long number) {
        return sino(Long.toString(number));
    }

    private static String chunkToKorean(int chunk) {
        StringBuilder out = new StringBuilder();
        String s = Integer.toString(chunk);
        for (int i = 0; i < s.length(); i++) {
            int d = s.charAt(i) - '0';
            int pos = s.length() - 1 - i;
            if (d == 0) {
                continue;
            }
            // 십, 백, 천 앞의 "일"은 읽지 않는다 (백, 십오)
            out.append(d == 1 && pos > 0 ? "" : DIGITS[d]).append(SMALL_UNITS[pos]);
        }
        return out.toString();
    }

    private static String digitsOneByOne(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) {
                out.append(c == '0' ? "영" : DIGITS[c - '0']).append(' ');
            }
        }
        return out.toString().trim();
    }

    /** 고유어 수 관형사: 3 → 세. 13 이상은 한자어로 읽는다. */
    public static String nativeCount(int n) {
        return n >= 1 && n < NATIVE.length ? NATIVE[n] : sino(n);
    }

    /** 자유 문장 속 숫자·단위를 낭독용으로 푼다: "1일 3회, 500mg" → "하루 세 번, 오백 밀리그램". */
    public static String normalize(String text) {
        if (text == null) {
            return null;
        }
        String s = PER_TAKE_RANGE.matcher(text).replaceAll(m -> Matcher.quoteReplacement(
                "한 번에 " + countOf(m.group(1), m.group(3)) + "에서 " + countOf(m.group(2), m.group(3))));
        s = RANGE.matcher(s).replaceAll(m -> Matcher.quoteReplacement(
                countOf(m.group(1), m.group(3)) + "에서 " + countOf(m.group(2), m.group(3))));
        s = PER_DAY.matcher(s).replaceAll(m -> "하루 " + nativeCount(Integer.parseInt(m.group(1))) + " 번");
        s = PER_TAKE.matcher(s).replaceAll(m -> Matcher.quoteReplacement("한 번에 " + countOf(m.group(1), m.group(2))));
        s = AMOUNT.matcher(s).replaceAll(m -> Matcher.quoteReplacement(countOf(m.group(1), m.group(2))));
        s = BARE_NUMBER.matcher(s).replaceAll(m -> Matcher.quoteReplacement(sino(m.group())));
        return s;
    }

    private static String countOf(String number, String unit) {
        String n = number.replace(",", "");
        boolean integer = !n.contains(".");
        return switch (unit) {
            case "mg", "㎎", "밀리그램", "밀리그람" -> sino(n) + " 밀리그램";
            case "g" -> sino(n) + " 그램";
            case "kg", "킬로그램" -> sino(n) + " 킬로그램";
            case "mcg", "µg", "μg", "㎍", "마이크로그램" -> sino(n) + " 마이크로그램";
            case "mL", "ml", "㎖", "밀리리터" -> sino(n) + " 밀리리터";
            case "L" -> sino(n) + " 리터";
            case "IU" -> sino(n) + " 아이유";
            case "%", "퍼센트" -> sino(n) + " 퍼센트";
            case "정", "알" -> pills(n, "알");
            case "캡슐" -> pills(n, "캡슐");
            case "포" -> pills(n, "포");
            case "회", "번" -> integer ? nativeCount(Integer.parseInt(n)) + " 번" : sino(n) + " 번";
            case "시간" -> integer ? nativeCount(Integer.parseInt(n)) + " 시간" : sino(n) + " 시간";
            case "개월" -> sino(n) + " 개월";
            case "주" -> sino(n) + " 주";
            case "일" -> sino(n) + " 일";
            case "분" -> sino(n) + " 분";
            case "세" -> sino(n) + " 세";
            default -> sino(n) + " " + unit;
        };
    }

    private static String pills(String n, String counter) {
        if (n.equals("0.5")) {
            return "반 " + counter;
        }
        if (n.contains(".")) {
            return sino(n) + " " + counter;
        }
        return nativeCount(Integer.parseInt(n)) + " " + counter;
    }
}
