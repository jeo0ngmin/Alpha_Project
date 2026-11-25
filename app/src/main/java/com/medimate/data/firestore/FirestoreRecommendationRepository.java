package com.medimate.data.firestore;

import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.medimate.data.RecommendationRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RecommendationRepository의 Firestore 구현. 경로: users/{uid}/recommendations/{자동 id} (README 9-2).
 * 기록 내용은 건강 정보라 로그에 남기지 않는다.
 */
public class FirestoreRecommendationRepository implements RecommendationRepository {

    private static final String TAG = "FirestoreRecommend";
    static final String COLLECTION = "recommendations";

    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    @Override
    public void save(List<String> concerns, List<String> ingredients) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null || ingredients.isEmpty()) {
            return;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("concerns", new ArrayList<>(concerns));
        fields.put("ingredients", new ArrayList<>(ingredients));
        fields.put("createdAt", FieldValue.serverTimestamp());
        db.collection("users").document(user.getUid()).collection(COLLECTION).add(fields)
                .addOnSuccessListener(ref -> Log.i(TAG, "추천 기록 저장함"))
                .addOnFailureListener(e -> Log.e(TAG, "추천 기록 저장 실패: " + FirestoreUserRepository.codeOf(e)));
    }
}
