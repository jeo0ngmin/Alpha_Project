package com.medimate.pill.model;

/** POST /pill-identification/{jobId}/select 본문. 내 약에 추가하는 것은 앱이 Firestore에 직접 한다. */
public class SelectRequest {
    public final String itemSeq;

    public SelectRequest(String itemSeq) {
        this.itemSeq = itemSeq;
    }
}
