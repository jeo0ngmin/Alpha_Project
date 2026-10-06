package com.medimate.auth;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;

/**
 * 로그인 상태의 단일 창구 (README 7-1). 화면은 로그인 방식을 몰라도 되도록 이 클래스만 참조한다.
 * 구글·전화번호 Helper가 만든 Firebase 자격 증명을 받아 로그인한다.
 */
public class AuthRepository {

    private static final String TAG = "AuthRepository";

    static final String MSG_NETWORK = "인터넷 연결을 확인해 주세요.";
    static final String MSG_INVALID = "인증 정보가 올바르지 않아요. 다시 시도해 주세요.";
    static final String MSG_COLLISION = "다른 방법으로 이미 가입된 계정이에요.";
    static final String MSG_FAILED = "로그인에 실패했어요. 다시 시도해 주세요.";

    private final FirebaseAuth auth = FirebaseAuth.getInstance();

    /** 로그인된 사용자. 없으면 null */
    @Nullable
    public FirebaseUser currentUser() {
        return auth.getCurrentUser();
    }

    public boolean isSignedIn() {
        return auth.getCurrentUser() != null;
    }

    public void signInWithCredential(AuthCredential credential, AuthCallback callback) {
        auth.signInWithCredential(credential).addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null || task.getResult().getUser() == null) {
                Exception e = task.getException();
                Log.e(TAG, "signInWithCredential 실패", e);
                callback.onError(toMessage(e));
                return;
            }
            AuthResult result = task.getResult();
            boolean isNewUser = result.getAdditionalUserInfo() != null
                    && result.getAdditionalUserInfo().isNewUser();
            callback.onSuccess(result.getUser(), isNewUser);
        });
    }

    /** Firebase 로그아웃. 카카오 로그아웃은 B4에서 함께 붙인다 (README 7-6). */
    public void signOut() {
        auth.signOut();
    }

    static String toMessage(@Nullable Exception e) {
        if (e instanceof FirebaseNetworkException) {
            return MSG_NETWORK;
        }
        if (e instanceof FirebaseAuthInvalidCredentialsException) {
            return MSG_INVALID;
        }
        if (e instanceof FirebaseAuthUserCollisionException) {
            return MSG_COLLISION;
        }
        return MSG_FAILED;
    }
}
