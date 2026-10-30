package com.medimate.data.firestore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.medimate.data.UserRepository;

import org.junit.Test;

import java.util.Map;

public class FirestoreUserRepositoryTest {

    @Test
    public void newProfileKeepsNameAndProvider() {
        Map<String, Object> fields = FirestoreUserRepository.newProfile(" 홍길동 ", UserRepository.PROVIDER_GOOGLE);

        assertEquals("홍길동", fields.get("name"));
        assertEquals("google", fields.get("provider"));
    }

    /** 이름을 모르면(전화번호 로그인 등) name 필드를 넣지 않는다 */
    @Test
    public void newProfileSkipsUnknownName() {
        assertFalse(FirestoreUserRepository.newProfile(null, UserRepository.PROVIDER_PHONE).containsKey("name"));
        assertFalse(FirestoreUserRepository.newProfile("  ", UserRepository.PROVIDER_PHONE).containsKey("name"));
    }
}
