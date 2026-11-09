package com.medimate.bag.model;

public enum BagJobStatus {
    /** 읽는 중. 계속 조회한다. */
    PROCESSING,
    /** 다 읽었고 확인할 약이 없다. */
    COMPLETED,
    /** 다 읽었지만 사용자가 확인할 약(needsReview)이 남아 있다. */
    PARTIAL,
    /** 읽지 못했다. error를 본다. */
    FAILED,
    /** 일정으로 확정했다. */
    CONFIRMED;

    /** 더 조회할 필요가 없는 상태. */
    public boolean isTerminal() {
        return this != PROCESSING;
    }
}
