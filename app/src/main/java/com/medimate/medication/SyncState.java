package com.medimate.medication;

/** 기기에 저장된 일정·기록이 서버에 반영된 상태. 화면에서 동기화 대기와 저장 실패를 구분해 보여 준다. */
public final class SyncState {

    /** 서버 반영을 기다리는 중 (오프라인 등). 연결되면 자동으로 다시 보낸다 */
    public static final int PENDING = 0;
    public static final int SYNCED = 1;
    /** 서버가 거부함 (권한 등). 사용자가 "다시 시도"를 누르면 다시 보낸다 */
    public static final int FAILED = 2;

    private SyncState() {
    }
}
