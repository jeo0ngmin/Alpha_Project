package com.medimate.data.medication;

import androidx.annotation.Nullable;

import com.medimate.medication.IntakeRecord;
import com.medimate.medication.MedicationSchedule;

import java.util.List;

/**
 * 복약 데이터의 서버 저장소. 나중에 Spring Boot 등으로 바꿀 때 구현만 교체한다 (README 9-2).
 * 모든 저장·조회 메서드는 호출한 스레드를 막으므로 백그라운드에서 부른다.
 */
public interface RemoteMedicationStore {

    /** 서버에 저장할 수 있는 로그인 사용자. 없으면 null (카카오는 아직 Firebase 사용자가 아니다) */
    @Nullable
    String currentUid();

    /** 로그인 상태가 바뀔 때마다, 그리고 등록 직후 한 번 불린다 */
    void addAccountListener(Runnable listener);

    void putSchedule(MedicationSchedule schedule) throws RemoteStoreException;

    void putIntake(IntakeRecord record) throws RemoteStoreException;

    List<MedicationSchedule> fetchSchedules(String uid) throws RemoteStoreException;

    /** from~to(yyyy-MM-dd) 예정일의 기록만 가져온다 */
    List<IntakeRecord> fetchIntakes(String uid, String from, String to) throws RemoteStoreException;
}
