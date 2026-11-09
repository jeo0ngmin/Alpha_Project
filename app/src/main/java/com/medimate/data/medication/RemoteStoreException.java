package com.medimate.data.medication;

/** 서버 저장·조회 실패. 일시적이면 동기화 대기로 두고 재시도하고, 아니면 저장 실패로 표시한다. */
public class RemoteStoreException extends Exception {

    private final boolean temporary;

    public RemoteStoreException(String code, boolean temporary) {
        super(code);
        this.temporary = temporary;
    }

    public boolean isTemporary() {
        return temporary;
    }
}
