package com.medimate.auth;

import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.google.android.gms.tasks.Tasks;

import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.PhoneAuthProvider;
import com.google.firebase.auth.UserInfo;
import com.medimate.data.UserRepository;

import java.util.concurrent.TimeUnit;

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
    public static final String MSG_REAUTH = "안전을 위해 다시 로그인한 뒤 탈퇴를 한 번 더 눌러 주세요.";
    static final String MSG_DELETE_FAILED = "탈퇴하지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요.";

    /** Firebase는 로그인한 지 오래되면 계정 삭제를 막는다. 그 기준(약 5분)보다 조금 짧게 잡는다 */
    private static final long RECENT_LOGIN_MS = 4 * 60 * 1000L;

    public interface DeleteCallback {
        /** 메인 스레드에서 불린다 */
        void onDeleted();

        /** 메인 스레드에서 불린다. message는 사용자에게 읽어 줄 문장 */
        void onError(String message);
    }

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

    /** 서버 요청에 붙일 Firebase ID 토큰 (server/README.md). 로그인 안 했으면 null. 메인 스레드에서 부르지 않는다 */
    @Nullable
    @WorkerThread
    public String idTokenBlocking() throws Exception {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            return null;
        }
        return Tasks.await(user.getIdToken(false), 20, TimeUnit.SECONDS).getToken();
    }

    /** 로그인 방식 (UserRepository.PROVIDER_*). 구글·전화번호가 아니면 "unknown" */
    public static String providerOf(FirebaseUser user) {
        for (UserInfo info : user.getProviderData()) {
            if (GoogleAuthProvider.PROVIDER_ID.equals(info.getProviderId())) {
                return UserRepository.PROVIDER_GOOGLE;
            }
            if (PhoneAuthProvider.PROVIDER_ID.equals(info.getProviderId())) {
                return UserRepository.PROVIDER_PHONE;
            }
        }
        return "unknown";
    }

    /** 계정 삭제가 가능할 만큼 최근에 로그인했는지. 아니면 데이터를 지우기 전에 다시 로그인하게 한다 */
    public boolean hasRecentLogin() {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null || user.getMetadata() == null) {
            return false;
        }
        return System.currentTimeMillis() - user.getMetadata().getLastSignInTimestamp() < RECENT_LOGIN_MS;
    }

    /**
     * 탈퇴: Firebase 계정 삭제 (README 7-6). 데이터는 먼저 UserRepository.deleteAllData로 지운다.
     * 삭제되면 로그아웃 상태가 되어 기기 안 복약 데이터·알림은 MedicationRepository가 지운다 (README 9-6).
     */
    public void deleteAccount(DeleteCallback callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            callback.onError(MSG_DELETE_FAILED);
            return;
        }
        user.delete().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                new KakaoSignInHelper().signOut();
                callback.onDeleted();
                return;
            }
            Exception e = task.getException();
            Log.e(TAG, "계정 삭제 실패: " + (e == null ? "unknown" : e.getClass().getSimpleName()));
            if (e instanceof FirebaseAuthRecentLoginRequiredException) {
                signOut();
                callback.onError(MSG_REAUTH);
            } else {
                callback.onError(e instanceof FirebaseNetworkException ? MSG_NETWORK : MSG_DELETE_FAILED);
            }
        });
    }

    /** 로그아웃: Firebase + 카카오 (README 7-6). 카카오로 로그인하지 않았으면 카카오 쪽은 실패 로그만 남는다. */
    public void signOut() {
        auth.signOut();
        new KakaoSignInHelper().signOut();
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
