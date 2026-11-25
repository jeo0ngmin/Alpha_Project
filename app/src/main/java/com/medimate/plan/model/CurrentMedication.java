package com.medimate.plan.model;

/**
 * 지금 먹고 있는 약 하나. 복약 일정은 앱이 Firestore에 저장하므로 서버는 모른다.
 * "이미 먹는 약" 확인이 필요한 요청(알약 식별, 약봉투 확정)에 앱이 목록으로 보낸다.
 * itemSeq가 있으면 그것으로, 없으면 이름(공백 무시)으로 비교한다.
 */
public class CurrentMedication {
    public final String itemSeq;
    public final String name;

    public CurrentMedication(String itemSeq, String name) {
        this.itemSeq = itemSeq;
        this.name = name;
    }

    public static CurrentMedication of(String itemSeq) {
        return new CurrentMedication(itemSeq, null);
    }
}
