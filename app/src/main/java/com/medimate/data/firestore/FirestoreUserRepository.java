package com.medimate.data.firestore;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.medimate.data.UserRepository;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UserRepository의 Firestore 구현 (README 9-2). 문서 경로: users/{uid}.
 * 본인 문서만 읽고 쓸 수 있게 보안 규칙을 건다 (README 9-3, firestore.rules).
 */
public class FirestoreUserRepository implements UserRepository {

    private static final String TAG = "FirestoreUser";

    static final String MSG_NETWORK = "인터넷 연결을 확인해 주세요.";
    static final String MSG_DENIED = "내 정보를 저장할 권한이 없어요. 다시 로그인해 주세요.";
    static final String MSG_FAILED = "내 정보를 저장하지 못했어요.";

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

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

    /** 첫 로그인 때 저장할 필드 (createdAt 제외). 모르는 값은 넣지 않는다 */
    static Map<String, Object> newProfile(@Nullable String name, String provider) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (name != null && !name.trim().isEmpty()) {
            fields.put("name", name.trim());
        }
        fields.put("provider", provider);
        return fields;
    }

    private static String codeOf(Exception e) {
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
