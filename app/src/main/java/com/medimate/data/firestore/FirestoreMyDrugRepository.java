package com.medimate.data.firestore;

import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;
import com.medimate.data.MyDrugRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MyDrugRepository의 Firestore 구현. 경로: users/{uid}/medications/{약 id} (README 9-2).
 * 약 id는 itemSeq, e약은요에서 못 찾은 약은 이름으로 만든 값이라 같은 약을 두 번 넣어도 하나만 남는다.
 * 약 이름은 개인정보라 로그에 남기지 않는다.
 */
public class FirestoreMyDrugRepository implements MyDrugRepository {

    private static final String TAG = "FirestoreMyDrug";
    static final String COLLECTION = "medications";

    static final String MSG_NOT_SIGNED_IN = "내 약은 구글이나 전화번호로 로그인하면 쓸 수 있어요.";
    static final String MSG_SAVE_FAILED = "내 약을 저장하지 못했어요.";
    static final String MSG_LOAD_FAILED = "내 약을 불러오지 못했어요.";
    static final String MSG_REMOVE_FAILED = "내 약을 지우지 못했어요.";

    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    @Override
    public boolean isAvailable() {
        return auth.getCurrentUser() != null;
    }

    @Override
    public void add(String itemSeq, String name, String dosage, String storage, String source,
                    Callback<Void> callback) {
        CollectionReference col = collection();
        if (col == null) {
            callback.onError(MSG_NOT_SIGNED_IN);
            return;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("itemSeq", itemSeq);
        fields.put("name", name);
        fields.put("dosage", dosage);
        fields.put("storage", storage);
        fields.put("source", source);
        fields.put("updatedAt", FieldValue.serverTimestamp());
        String id = idOf(itemSeq, name);
        // createdAt은 처음 넣을 때만. 목록 순서가 바뀌지 않게 한다
        db.runTransaction(tx -> {
            boolean exists = tx.get(col.document(id)).exists();
            if (!exists) {
                fields.put("createdAt", FieldValue.serverTimestamp());
            }
            tx.set(col.document(id), fields, SetOptions.merge());
            return null;
        }).addOnSuccessListener(v -> callback.onSuccess(null))
          .addOnFailureListener(e -> {
              Log.e(TAG, "내 약 저장 실패: " + FirestoreUserRepository.codeOf(e));
              callback.onError(errorMessage(e, MSG_SAVE_FAILED));
          });
    }

    @Override
    public void list(Callback<List<MyDrug>> callback) {
        CollectionReference col = collection();
        if (col == null) {
            callback.onError(MSG_NOT_SIGNED_IN);
            return;
        }
        col.orderBy("createdAt", Query.Direction.ASCENDING).get()
                .addOnSuccessListener(snapshot -> {
                    List<MyDrug> drugs = new ArrayList<>();
                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        drugs.add(new MyDrug(doc.getId(), text(doc, "itemSeq"), text(doc, "name"),
                                text(doc, "dosage"), text(doc, "storage")));
                    }
                    callback.onSuccess(drugs);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "내 약 조회 실패: " + FirestoreUserRepository.codeOf(e));
                    callback.onError(errorMessage(e, MSG_LOAD_FAILED));
                });
    }

    @Override
    public void remove(String id, Callback<Void> callback) {
        CollectionReference col = collection();
        if (col == null) {
            callback.onError(MSG_NOT_SIGNED_IN);
            return;
        }
        col.document(id).delete()
                .addOnSuccessListener(v -> callback.onSuccess(null))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "내 약 삭제 실패: " + FirestoreUserRepository.codeOf(e));
                    callback.onError(errorMessage(e, MSG_REMOVE_FAILED));
                });
    }

    private CollectionReference collection() {
        FirebaseUser user = auth.getCurrentUser();
        return user == null ? null
                : db.collection("users").document(user.getUid()).collection(COLLECTION);
    }

    /** itemSeq가 있으면 그대로, 없으면 이름의 해시 (문서 id에 "/"가 들어가지 않게) */
    static String idOf(String itemSeq, String name) {
        if (itemSeq != null && !itemSeq.trim().isEmpty()) {
            return itemSeq.trim();
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(name.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("name_");
            for (int i = 0; i < 12; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(DocumentSnapshot doc, String field) {
        String value = doc.getString(field);
        return value == null ? "" : value;
    }

    private static String errorMessage(Exception e, String fallback) {
        String message = FirestoreUserRepository.toMessage(e);
        return FirestoreUserRepository.MSG_FAILED.equals(message) ? fallback : message;
    }
}
