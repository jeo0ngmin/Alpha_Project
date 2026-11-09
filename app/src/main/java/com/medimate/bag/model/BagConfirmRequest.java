package com.medimate.bag.model;

import java.util.List;
import java.util.Map;

import com.medimate.plan.model.CurrentMedication;

/** POST /recognition/jobs/{jobId}/confirm 본문. 모두 선택값이다. */
public class BagConfirmRequest {
    /** yyyy-MM-dd. 없으면 오늘 */
    public String startDate;
    public List<String> excludeItemIds;
    /** {"MORNING": "07:30"}처럼 시점별 알림 시각을 바꾼다 */
    public Map<String, String> timeSlotOverrides;
    /**
     * true면 약마다 확인하지 않고 확정한다(확인 화면이 없는 경우).
     * 복용량·횟수·일수를 못 읽은 약과 이미 먹고 있는 약은 서버가 빼고 skippedItems로 알려 준다.
     */
    public Boolean acceptAll;
    /**
     * 앱(Firestore)에 저장된 복용 중인 약. 서버는 이 목록으로만 "이미 먹는 약"을 판단한다.
     * 보내지 않으면 확인하지 않는다. acceptAll이 아니면 겹칠 때 409(MM-RCG-4094).
     */
    public List<CurrentMedication> currentMedications;

    public static BagConfirmRequest acceptAll(List<CurrentMedication> currentMedications) {
        BagConfirmRequest r = new BagConfirmRequest();
        r.acceptAll = true;
        r.currentMedications = currentMedications;
        return r;
    }
}
