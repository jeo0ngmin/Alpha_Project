package com.medimate.data.firestore;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;
import com.google.firebase.firestore.WriteBatch;
import com.medimate.data.UserRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * UserRepository의 Firestore 구현 (README 9-2). 문서 경로: users/{uid}.
 * 본인 문서만 읽고 쓸 수 있게 보안 규칙을 건다 (README 9-3, firestore.rules).
 */
public class FirestoreUserRepository implements UserRepository {

    private static final String TAG = "FirestoreUser";

    static final String MSG_NETWORK = "인터넷 연결을 확인해 주세요.";
    static final String MSG_DENIED = "내 정보를 저장할 권한이 없어요. 다시 로그인해 주세요.";
    static final String MSG_FAILED = "내 정보를 저장하지 못했어요.";
    static final String MSG_DELETE_FAILED = "내 데이터를 지우지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요.";

    /**
     * users/{uid} 아래 하위 컬렉션 전체. Firestore는 문서를 지워도 하위 컬렉션이 남으므로 하나씩 지운다.
     * 새 하위 컬렉션을 만들면 여기에도 추가한다.
     */
    static final List<String> USER_SUBCOLLECTIONS = List.of(
            "medicationSchedules", "medicationIntakes",          // 복약 일정·기록 (FirestoreMedicationStore)
            FirestoreMyDrugRepository.COLLECTION,                 // 내 약
            FirestoreRecommendationRepository.COLLECTION);        // 추천 기록

    private static final int BATCH_LIMIT = 400;
    private static final long TIMEOUT_SECONDS = 30;
    private static final ExecutorService deleter = Executors.newSingleThreadExecutor();

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void ensureProfile(String uid, @Nullable String name, String provider, Callback callback) {
        DocumentReference doc = db.collection("users").document(uid);
        // 읽고 없을 때만 쓰기를 한 번에 해서, 여러 기기에서 동시에 로그인해도 createdAt이 덮어써지지 않게 한다
        db.runTransaction(tx -> {
            if (tx.get(doc).exists()) {
                return false;
            }
            Map<String, Object> fields = newProfile(name, provider);
            fields.put("createdAt", FieldValue.serverTimestamp());
            tx.set(doc, fields);
            return true;
        }).addOnSuccessListener(callback::onSuccess)
          .addOnFailureListener(e -> {
              // 이름 등 개인정보는 로그에 남기지 않는다
              Log.e(TAG, "프로필 저장 실패: " + codeOf(e));
              callback.onError(toMessage(e));
          });
    }

    @Override
    public void deleteAllData(String uid, DeleteCallback callback) {
        DocumentReference user = db.collection("users").document(uid);
        deleter.execute(() -> {
            try {
                for (String name : USER_SUBCOLLECTIONS) {
                    // 캐시만 지우고 끝났다고 착각하지 않도록 서버에서 읽는다. 400개씩 나눠 지운다
                    while (true) {
                        QuerySnapshot page = Tasks.await(user.collection(name).limit(BATCH_LIMIT)
                                .get(Source.SERVER), TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        if (page.isEmpty()) {
                            break;
                        }
                        WriteBatch batch = db.batch();
                        for (DocumentSnapshot doc : page.getDocuments()) {
                            batch.delete(doc.getReference());
                        }
                        Tasks.await(batch.commit(), TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    }
                }
                Tasks.await(user.delete(), TIMEOUT_SECONDS, TimeUnit.SECONDS);
                Log.i(TAG, "탈퇴: 사용자 데이터 삭제 완료");
                main.post(callback::onDeleted);
            } catch (Exception e) {
                Log.e(TAG, "탈퇴 데이터 삭제 실패: " + codeOf(e));
                main.post(() -> callback.onError(MSG_DELETE_FAILED));
            }
        });
    }

    /** 첫 로그인 때 저장할 필드 (createdAt 제외). 모르는 값은 넣지 않는다 */
    static Map<String, Object> newProfile(@Nullable String name, String provider) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (name != null && !name.trim().isEmpty()) {
            fields.put("name", name.trim());
        }
        fields.put("provider", provider);
        return fields;
    }

    static String codeOf(Exception e) {
        if (e instanceof java.util.concurrent.ExecutionException && e.getCause() instanceof Exception) {
            e = (Exception) e.getCause();
        }
        return e instanceof FirebaseFirestoreException
                ? ((FirebaseFirestoreException) e).getCode().name()
                : e.getClass().getSimpleName();
    }

    static String toMessage(Exception e) {
        if (e instanceof FirebaseFirestoreException) {
            FirebaseFirestoreException.Code code = ((FirebaseFirestoreException) e).getCode();
            if (code == FirebaseFirestoreException.Code.UNAVAILABLE) {
                return MSG_NETWORK;
            }
            if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                    || code == FirebaseFirestoreException.Code.UNAUTHENTICATED) {
                return MSG_DENIED;
            }
        }
        return MSG_FAILED;
    }
}
