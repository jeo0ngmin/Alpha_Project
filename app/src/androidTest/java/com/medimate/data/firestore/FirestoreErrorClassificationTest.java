package com.medimate.data.firestore;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.firebase.firestore.FirebaseFirestoreException;

import org.junit.Test;
import org.junit.runner.RunWith;

/** FirebaseFirestoreException.Code가 안드로이드 클래스를 써서 기기에서 확인한다 */
@RunWith(AndroidJUnit4.class)
public class FirestoreErrorClassificationTest {

    @Test
    public void 권한_거부는_저장_실패_네트워크는_동기화_대기다() {
        assertFalse(FirestoreMedicationStore.isTemporary(FirebaseFirestoreException.Code.PERMISSION_DENIED));
        assertTrue(FirestoreMedicationStore.isTemporary(FirebaseFirestoreException.Code.UNAVAILABLE));
        assertTrue(FirestoreMedicationStore.isTemporary(FirebaseFirestoreException.Code.DEADLINE_EXCEEDED));
    }
}
