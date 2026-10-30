package com.medimate.ui;

import android.util.Log;

import com.google.firebase.auth.FirebaseUser;
import com.medimate.auth.AuthRepository;
import com.medimate.data.UserRepository;
import com.medimate.data.firestore.FirestoreUserRepository;

/**
 * Firebase 로그인 사용자의 프로필 문서를 만든다 (README 10-2 D1).
 * 로그인 직후와 앱 시작 때 부른다. 실패해도 로그인 흐름은 막지 않는다.
 * 카카오 로그인은 아직 Firebase 사용자가 아니라서 저장하지 않는다 (README 7-5, 2차).
 */
final class ProfileSync {

    private static final String TAG = "ProfileSync";

    private ProfileSync() {
    }

    static void ensure(FirebaseUser user, Runnable done) {
        UserRepository repository = new FirestoreUserRepository();
        repository.ensureProfile(user.getUid(), user.getDisplayName(), AuthRepository.providerOf(user),
                new UserRepository.Callback() {
                    @Override
                    public void onSuccess(boolean created) {
                        Log.i(TAG, created ? "프로필 새로 만듦" : "프로필 이미 있음");
                        done.run();
                    }

                    @Override
                    public void onError(String message) {
                        done.run();
                    }
                });
    }
}
