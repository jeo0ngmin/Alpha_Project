package com.medimate.auth;

import com.google.firebase.auth.FirebaseUser;

/** 로그인 결과 전달. 메인 스레드에서 호출된다. */
public interface AuthCallback {

    /** @param isNewUser 처음 가입한 사용자면 true (ProfileSetup으로 보낼 때 쓴다) */
    void onSuccess(FirebaseUser user, boolean isNewUser);

    /** 사용자에게 그대로 읽어 줄 수 있는 실패 안내 문장. */
    void onError(String message);
}
