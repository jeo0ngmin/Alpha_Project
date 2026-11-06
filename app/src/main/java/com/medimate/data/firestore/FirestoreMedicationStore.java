package com.medimate.data.firestore;

import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;
import com.medimate.data.medication.RemoteMedicationStore;
import com.medimate.data.medication.RemoteStoreException;
import com.medimate.medication.IntakeRecord;
import com.medimate.medication.MedicationSchedule;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RemoteMedicationStore의 Firestore 구현 (README 9-2).
 * 경로: users/{uid}/medicationSchedules/{버전 id}, users/{uid}/medicationIntakes/{회차 키}.
 * 문서 id가 정해져 있어 네트워크 재시도나 동시 저장에도 같은 회차의 문서는 하나뿐이다.
 * 본인 문서만 접근하는 보안 규칙(README 9-3)이 하위 컬렉션까지 적용된다.
 */
public class FirestoreMedicationStore implements RemoteMedicationStore {

    private static final long TIMEOUT_SECONDS = 20;

    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    @Nullable
    @Override
    public String currentUid() {
        FirebaseUser user = auth.getCurrentUser();
        return user == null ? null : user.getUid();
    }

    @Override
    public void addAccountListener(Runnable listener) {
        auth.addAuthStateListener(a -> listener.run());
    }

    @Override
    public void putSchedule(MedicationSchedule schedule) throws RemoteStoreException {
        await(schedules(schedule.ownerUid).document(schedule.id).set(MedicationFirestoreMapper.toFields(schedule)));
    }

    @Override
    public void putIntake(IntakeRecord record) throws RemoteStoreException {
        await(intakes(record.ownerUid).document(record.occurrenceKey).set(MedicationFirestoreMapper.toFields(record)));
    }

    @Override
    public List<MedicationSchedule> fetchSchedules(String uid) throws RemoteStoreException {
        List<MedicationSchedule> result = new ArrayList<>();
        // 캐시로 대신 답하면 오프라인을 동기화 완료로 착각하므로 서버에서만 읽는다
        QuerySnapshot snapshot = await(schedules(uid).get(Source.SERVER));
        for (DocumentSnapshot doc : snapshot.getDocuments()) {
            MedicationSchedule schedule = MedicationFirestoreMapper.scheduleFrom(doc.getId(), uid, doc.getData());
            if (schedule != null) {
                result.add(schedule);
            }
        }
        return result;
    }

    @Override
    public List<IntakeRecord> fetchIntakes(String uid, String from, String to) throws RemoteStoreException {
        List<IntakeRecord> result = new ArrayList<>();
        QuerySnapshot snapshot = await(intakes(uid)
                .whereGreaterThanOrEqualTo("scheduledDate", from)
                .whereLessThanOrEqualTo("scheduledDate", to)
                .get(Source.SERVER));
        for (DocumentSnapshot doc : snapshot.getDocuments()) {
            IntakeRecord record = MedicationFirestoreMapper.intakeFrom(doc.getId(), uid, doc.getData());
            if (record != null) {
                result.add(record);
            }
        }
        return result;
    }

    private CollectionReference schedules(String uid) {
        return db.collection("users").document(uid).collection("medicationSchedules");
    }

    private CollectionReference intakes(String uid) {
        return db.collection("users").document(uid).collection("medicationIntakes");
    }

    private static <T> T await(Task<T> task) throws RemoteStoreException {
        try {
            return Tasks.await(task, TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 오프라인이면 저장 Task가 끝나지 않는다. 같은 문서 id로 다시 보내므로 중복되지 않는다
            throw new RemoteStoreException("TIMEOUT", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteStoreException("INTERRUPTED", true);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof FirebaseFirestoreException) {
                FirebaseFirestoreException.Code code = ((FirebaseFirestoreException) cause).getCode();
                throw new RemoteStoreException(code.name(), isTemporary(code));
            }
            throw new RemoteStoreException(cause == null ? "UNKNOWN" : cause.getClass().getSimpleName(), true);
        }
    }

    /** 권한 거부처럼 다시 보내도 같은 결과일 오류만 저장 실패로 본다 */
    static boolean isTemporary(FirebaseFirestoreException.Code code) {
        switch (code) {
            case PERMISSION_DENIED:
            case INVALID_ARGUMENT:
            case NOT_FOUND:
            case FAILED_PRECONDITION:
            case OUT_OF_RANGE:
            case UNIMPLEMENTED:
            case DATA_LOSS:
                return false;
            default:
                return true;
        }
    }
}
