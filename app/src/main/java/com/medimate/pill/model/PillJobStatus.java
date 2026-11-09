package com.medimate.pill.model;

public enum PillJobStatus {
    PENDING, PROCESSING, COMPLETED, NOT_FOUND, FAILED, SELECTED;

    /** 더 폴링할 필요가 없는 상태. */
    public boolean isTerminal() {
        return this != PENDING && this != PROCESSING;
    }
}
