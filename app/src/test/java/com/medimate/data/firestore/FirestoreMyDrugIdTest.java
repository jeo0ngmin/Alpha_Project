package com.medimate.data.firestore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FirestoreMyDrugIdTest {

    @Test
    public void usesItemSeqWhenPresent() {
        assertEquals("202005623", FirestoreMyDrugRepository.idOf(" 202005623 ", "타이레놀정500밀리그람"));
    }

    @Test
    public void sameNameGivesSameIdWithoutSlash() {
        String a = FirestoreMyDrugRepository.idOf("", "알수없는약 1/2정");
        String b = FirestoreMyDrugRepository.idOf("", " 알수없는약 1/2정 ");
        assertEquals(a, b);
        assertTrue(a.startsWith("name_"));
        assertFalse(a.contains("/"));
        assertNotEquals(a, FirestoreMyDrugRepository.idOf("", "다른약"));
    }

    @Test
    public void deletionCoversAllUserCollections() {
        assertTrue(FirestoreUserRepository.USER_SUBCOLLECTIONS.contains(FirestoreMyDrugRepository.COLLECTION));
        assertTrue(FirestoreUserRepository.USER_SUBCOLLECTIONS.contains(FirestoreRecommendationRepository.COLLECTION));
        assertTrue(FirestoreUserRepository.USER_SUBCOLLECTIONS.contains("medicationSchedules"));
        assertTrue(FirestoreUserRepository.USER_SUBCOLLECTIONS.contains("medicationIntakes"));
    }
}
