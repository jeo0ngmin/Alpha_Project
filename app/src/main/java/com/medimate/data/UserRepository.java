package com.medimate.data;

import androidx.annotation.Nullable;

/**
 * 사용자 프로필 저장소 (README 9-4). 화면은 Firestore 대신 이 인터페이스만 참조한다.
 * Firebase 타입에 묶이지 않게 uid·이름·로그인 방식만 받는다. 나중에 다른 서버로 바꿀 때 구현만 교체한다.
 */
public interface UserRepository {

    String PROVIDER_GOOGLE = "google";
    String PROVIDER_PHONE = "phone";
    String PROVIDER_KAKAO = "kakao";

    interface Callback {
        /** @param created 이번에 새로 만들었으면 true, 이미 있었으면 false. 메인 스레드에서 불린다 */
        void onSuccess(boolean created);

        /** 메인 스레드에서 불린다. message는 사용자에게 읽어 줄 문장 */
        void onError(String message);
    }

    /**
     * 프로필 문서가 없으면 만든다 (첫 로그인). 이미 있으면 건드리지 않는다.
     *
     * @param name     로그인 계정의 이름. 모르면 null (생년·성별은 프로필 입력 화면에서 받는다, 2차)
     * @param provider PROVIDER_GOOGLE / PROVIDER_PHONE / PROVIDER_KAKAO
     */
    void ensureProfile(String uid, @Nullable String name, String provider, Callback callback);
}
