package com.medimate.server.recognition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

import com.medimate.server.speech.SpeechFormatter;

/**
 * 약봉투 결과를 읽어 줄 문장 (설계서 03 §5.4). AI 출력을 그대로 읽지 않고 검증된 값으로 조립한다.
 * 숫자는 한글로 풀어 써서 기기 TTS가 "1정"을 "일정"으로 읽는 일이 없게 한다.
 */
public final class BagSpeech {

    private BagSpeech() {
    }

    public static String ordinal(int n) {
        return switch (n) {
            case 1 -> "첫";
            case 2 -> "두";
            case 3 -> "세";
            case 4 -> "네";
            case 5 -> "다섯";
            default -> SpeechFormatter.sino(n);
        } + " 번째";
    }

    static String number(double v) {
        return SpeechFormatter.sino(BigDecimal.valueOf(v).stripTrailingZeros().toPlainString());
    }

    /** 1회량: 반 알, 한 알, 두 캡슐, 오 밀리리터. */
    public static String amount(Double dose, String unit) {
        if (dose == null) {
            return null;
        }
        String counter = switch (unit == null ? "TABLET" : unit) {
            case "CAPSULE" -> "캡슐";
            case "PACK" -> "포";
            case "ML" -> null;
            case "TABLET" -> "알";
            default -> "개";
        };
        if (counter == null) {
            return number(dose) + " 밀리리터";
        }
        if (dose == 0.5) {
            return "반 " + counter;
        }
        boolean whole = dose == Math.floor(dose) && dose <= 12;
        return (whole ? SpeechFormatter.nativeCount((int) (double) dose) : number(dose)) + " " + counter;
    }

    public static String slot(String slot) {
        return switch (slot) {
            case "MORNING" -> "아침";
            case "LUNCH" -> "점심";
            case "DINNER" -> "저녁";
            case "BEDTIME" -> "자기 전";
            default -> "정해진 시간";
        };
    }

    public static String slots(List<String> slots) {
        return slots.stream().map(BagSpeech::slot).collect(Collectors.joining(", "));
    }

    /** 식후 삼십 분, 식전, 식사와 함께. 관계가 없거나 상관없으면 빈 문자열. */
    public static String meal(String relation, Integer offsetMinutes) {
        if (relation == null) {
            return "";
        }
        String offset = offsetMinutes == null || offsetMinutes <= 0 ? "" : " " + SpeechFormatter.sino(offsetMinutes) + " 분";
        return switch (relation) {
            case "AFTER_MEAL" -> "식후" + offset;
            case "BEFORE_MEAL" -> "식전" + offset;
            case "WITH_MEAL" -> "식사와 함께";
            case "EMPTY_STOMACH" -> "공복";
            default -> "";
        };
    }

    /** 오전 여덟 시, 오후 여섯 시 삼십 분. */
    public static String time(LocalTime t) {
        int h = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        String text = (t.getHour() < 12 ? "오전 " : "오후 ") + SpeechFormatter.nativeCount(h) + " 시";
        return t.getMinute() == 0 ? text : text + " " + SpeechFormatter.sino(t.getMinute()) + " 분";
    }

    public static String day(LocalDate date, LocalDate today) {
        if (date.equals(today)) {
            return "오늘";
        }
        if (date.equals(today.plusDays(1))) {
            return "내일";
        }
        return SpeechFormatter.sino(date.getMonthValue()) + " 월 " + SpeechFormatter.sino(date.getDayOfMonth()) + " 일";
    }

    /** 괄호 안 성분명까지 읽으면 길어지므로 이름만 읽는다. */
    public static String name(String itemName) {
        return SpeechFormatter.normalize(itemName.replaceAll("\\([^)]*\\)", "").trim());
    }

    /** "첫 번째 약, 타이레놀정 오백 밀리그램. 한 번에 한 알, 하루 세 번, 아침, 점심, 저녁 식후 삼십 분에 드세요. 오 일 동안 드세요." */
    public static String item(int index, RecognitionDtos.BagItem it) {
        StringBuilder s = new StringBuilder(ordinal(index)).append(" 약, ").append(name(it.displayName())).append(".");
        String amount = amount(it.dosageAmount, it.dosageUnit);
        if (amount != null) {
            s.append(" 한 번에 ").append(amount).append(",");
        }
        if (it.frequencyPerDay != null) {
            s.append(" 하루 ").append(SpeechFormatter.nativeCount(it.frequencyPerDay)).append(" 번,");
        }
        if (it.timeSlots != null && !it.timeSlots.isEmpty()) {
            String meal = meal(it.mealRelation, it.mealOffsetMinutes);
            s.append(" ").append(slots(it.timeSlots)).append(meal.isEmpty() ? "" : " " + meal).append("에 드세요.");
        } else if (s.charAt(s.length() - 1) == ',') {
            s.setCharAt(s.length() - 1, '.');
        }
        if (it.durationDays != null) {
            s.append(" ").append(SpeechFormatter.sino(it.durationDays)).append(" 일 동안 드세요.");
        }
        if (it.cautions != null && !it.cautions.isEmpty()) {
            s.append(" 주의할 점입니다. ").append(sentences(it.cautions));
        }
        if (it.slotsInferred) {
            s.append(" 복용 시간이 적혀 있지 않아 ").append(slots(it.timeSlots)).append("으로 정했습니다. 맞는지 확인해 주세요.");
        }
        if (it.needsReview && !it.excluded) {
            s.append(" 이 약은 ").append(reason(it)).append(" 가족이나 약사에게 확인해 주세요.");
        }
        return s.toString();
    }

    /** 이유는 추론 서버가 "~습니다" 문장으로 준다. 첫 번째 이유만 읽는다. */
    private static String reason(RecognitionDtos.BagItem it) {
        if (it.reviewReasons == null || it.reviewReasons.isEmpty()) {
            return "확인이 필요합니다.";
        }
        String first = it.reviewReasons.get(0).trim();
        return "확인이 필요합니다. " + first + (first.endsWith(".") ? "" : ".");
    }

    private static String sentences(List<String> texts) {
        return texts.stream().map(t -> SpeechFormatter.normalize(t.trim()))
                .map(t -> t.endsWith(".") || t.endsWith("요") || t.endsWith("다") ? t + (t.endsWith(".") ? "" : ".") : t + ".")
                .collect(Collectors.joining(" "));
    }

    /** 화면용 한 줄: "1회 1정 · 하루 3회 · 아침, 점심, 저녁 · 식후 30분 · 5일분". 모르는 값은 빼고, 숫자는 그대로 둔다. */
    public static String display(RecognitionDtos.BagItem it) {
        List<String> parts = new java.util.ArrayList<>();
        if (it.dosageAmount != null) {
            parts.add("1회 " + BigDecimal.valueOf(it.dosageAmount).stripTrailingZeros().toPlainString() + unitLabel(it.dosageUnit));
        }
        if (it.frequencyPerDay != null) {
            parts.add("하루 " + it.frequencyPerDay + "회");
        }
        if (it.timeSlots != null && !it.timeSlots.isEmpty()) {
            parts.add(slots(it.timeSlots));
        }
        String meal = mealLabel(it.mealRelation, it.mealOffsetMinutes);
        if (!meal.isEmpty()) {
            parts.add(meal);
        }
        if (it.durationDays != null) {
            parts.add(it.durationDays + "일분");
        }
        return String.join(" · ", parts);
    }

    private static String unitLabel(String unit) {
        return switch (unit == null ? "TABLET" : unit) {
            case "CAPSULE" -> "캡슐";
            case "PACK" -> "포";
            case "ML" -> "mL";
            case "TABLET" -> "정";
            default -> "개";
        };
    }

    private static String mealLabel(String relation, Integer offset) {
        String minutes = offset == null || offset <= 0 ? "" : " " + offset + "분";
        if (relation == null) {
            return "";
        }
        return switch (relation) {
            case "AFTER_MEAL" -> "식후" + minutes;
            case "BEFORE_MEAL" -> "식전" + minutes;
            case "WITH_MEAL" -> "식사와 함께";
            case "EMPTY_STOMACH" -> "공복";
            default -> "";
        };
    }

    public static String summary(List<RecognitionDtos.BagItem> items, List<String> generalCautions) {
        List<RecognitionDtos.BagItem> active = items.stream().filter(i -> !i.excluded).toList();
        long review = active.stream().filter(i -> i.needsReview).count();
        StringBuilder s = new StringBuilder("약 ").append(SpeechFormatter.nativeCount(active.size())).append(" 가지를 찾았습니다.");
        for (int i = 0; i < active.size(); i++) {
            s.append(" ").append(item(i + 1, active.get(i)));
        }
        if (generalCautions != null && !generalCautions.isEmpty()) {
            s.append(" 모든 약에 대한 주의할 점입니다. ").append(sentences(generalCautions));
        }
        if (review > 0) {
            s.append(" 확인이 필요한 약이 ").append(SpeechFormatter.nativeCount((int) review)).append(" 가지 있습니다.");
        }
        return s.append(" 맞으면 '맞아요', 다시 찍으려면 '다시 찍기'를 누르세요.").toString();
    }
}
