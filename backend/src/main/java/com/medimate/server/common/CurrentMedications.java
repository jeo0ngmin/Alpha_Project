package com.medimate.server.common;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.validation.constraints.Size;

/**
 * 사용자가 지금 먹고 있는 약. 복약 일정은 앱(Firestore)에 저장되므로 서버는 일정을 보관하지 않고,
 * "이미 먹는 약" 확인이 필요한 요청에 앱이 이 목록을 함께 보낸다.
 */
public final class CurrentMedications {

    private CurrentMedications() {
    }

    /** itemSeq(식약처 품목기준코드)가 있으면 그것으로, 없으면 이름으로 비교한다. */
    public record Entry(@Size(max = 30) String itemSeq, @Size(max = 400) String name) {
    }

    /** 한 요청에 받는 최대 개수. 앱의 "복용 중" 약은 이보다 훨씬 적다. */
    public static final int MAX = 100;

    /** 비교용 키 모음. null이나 빈 목록이면 빈 집합. */
    public static Set<String> keys(List<Entry> entries) {
        Set<String> out = new HashSet<>();
        if (entries == null) {
            return out;
        }
        if (entries.size() > MAX) {
            throw ApiException.badRequest("currentMedications", "복용 중인 약은 " + MAX + "개까지 보낼 수 있습니다.");
        }
        for (Entry e : entries) {
            if (e == null) {
                continue;
            }
            String k = key(e.itemSeq(), e.name());
            if (k != null) {
                out.add(k);
            }
        }
        return out;
    }

    /** 같은 약인지 비교할 키. itemSeq와 이름이 모두 없으면 null. */
    public static String key(String itemSeq, String name) {
        if (itemSeq != null && !itemSeq.isBlank()) {
            return "seq:" + itemSeq.trim();
        }
        if (name != null && !name.isBlank()) {
            return "name:" + name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        }
        return null;
    }
}
