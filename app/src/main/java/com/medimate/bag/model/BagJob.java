package com.medimate.bag.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** GET /recognition/jobs/{jobId} 응답: 약봉투 인식 결과. */
public class BagJob {
    public String jobId;
    public String type;
    public BagJobStatus status;
    public Double overallConfidence;
    public String processedAt;
    public Long processingMs;
    public BagSource source;
    public List<BagItem> items;
    /** 사용자가 확인해야 하는 약 수 (needsReview이고 빼지 않은 약). */
    public int reviewRequiredCount;
    /** 결과 화면에 들어왔을 때 읽어 줄 요약. */
    public String summaryTtsScript;
    /** status가 FAILED일 때만 있다. */
    public JobError error;
    public List<String> generalInstructions;
    public List<String> generalCautions;

    public boolean isFinished() {
        return status != null && status.isTerminal();
    }

    public List<BagItem> itemsOrEmpty() {
        return items == null ? Collections.emptyList() : items;
    }

    /** 결과 목록에 보여 줄 약: 사용자가 뺀 약(excluded)은 제외. */
    public List<BagItem> visibleItems() {
        List<BagItem> out = new ArrayList<>();
        for (BagItem it : itemsOrEmpty()) {
            if (!it.excluded) {
                out.add(it);
            }
        }
        return out;
    }

    /** itemId로 약을 찾는다. 없으면 null. */
    public BagItem item(String itemId) {
        for (BagItem it : itemsOrEmpty()) {
            if (it.itemId != null && it.itemId.equals(itemId)) {
                return it;
            }
        }
        return null;
    }
}
