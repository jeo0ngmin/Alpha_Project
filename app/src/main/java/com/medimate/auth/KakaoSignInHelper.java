package com.medimate.auth;

import android.content.Context;
import android.util.Log;

import com.kakao.sdk.auth.model.OAuthToken;
import com.kakao.sdk.common.model.ClientError;
import com.kakao.sdk.common.model.ClientErrorCause;
import com.kakao.sdk.user.UserApiClient;
import com.kakao.sdk.user.model.User;

import kotlin.Unit;

/**
 * 카카오 SDK 로그인 (README 7-5, 1차 뼈대). 카카오톡이 있으면 카카오톡으로, 없거나 실패하면 카카오 계정으로.
 * Firebase 연결은 2차. 그때까지 카카오 사용자는 Firebase 사용자가 아니다.
 */
public class KakaoSignInHelper {

    private static final String TAG = "KakaoSignIn";

    static final String MSG_CANCELLED = "로그인을 취소했어요.";
    static final String MSG_FAILED = "카카오 로그인에 실패했어요. 다시 시도해 주세요.";
    static final String MSG_NO_PROFILE = "카카오 계정 정보를 가져오지 못했어요.";

    public interface KakaoCallback {
        void onSuccess(long kakaoId, String nickname);

        /** 사용자에게 그대로 읽어 줄 수 있는 실패 안내 문장. */
        void onError(String message);
    }

    public void signIn(Context context, KakaoCallback callback) {
        UserApiClient client = UserApiClient.getInstance();
        if (client.isKakaoTalkLoginAvailable(context)) {
            client.loginWithKakaoTalk(context, (token, error) -> {
                if (error != null) {
                    // 사용자가 카카오톡 로그인을 취소한 경우는 계정 로그인으로 넘기지 않는다
                    if (isCancelled(error)) {
                        callback.onError(MSG_CANCELLED);
                    } else {
                        Log.w(TAG, "카카오톡 로그인 실패, 카카오 계정 로그인으로 전환", error);
                        loginWithAccount(context, callback);
                    }
                } else {
                    fetchProfile(callback);
                }
                return Unit.INSTANCE;
            });
        } else {
            loginWithAccount(context, callback);
        }
    }

    private void loginWithAccount(Context context, KakaoCallback callback) {
        UserApiClient.getInstance().loginWithKakaoAccount(context, (token, error) -> {
            if (error != null) {
                if (isCancelled(error)) {
                    callback.onError(MSG_CANCELLED);
                } else {
                    Log.e(TAG, "카카오 계정 로그인 실패", error);
                    callback.onError(MSG_FAILED);
                }
            } else {
                fetchProfile(callback);
            }
            return Unit.INSTANCE;
        });
    }

    private void fetchProfile(KakaoCallback callback) {
        UserApiClient.getInstance().me((user, error) -> {
            if (error != null || user == null || user.getId() == null) {
                Log.e(TAG, "사용자 정보 조회 실패", error);
                callback.onError(MSG_NO_PROFILE);
            } else {
                callback.onSuccess(user.getId(), nicknameOf(user));
            }
            return Unit.INSTANCE;
        });
    }

    /** 로그아웃 (README 7-6). 실패해도 토큰은 지워진다. */
    public void signOut() {
        UserApiClient.getInstance().logout(error -> {
            if (error != null) {
                Log.w(TAG, "카카오 로그아웃 실패", error);
            }
            return Unit.INSTANCE;
        });
    }

    private static boolean isCancelled(Throwable error) {
        return error instanceof ClientError
                && ((ClientError) error).getReason() == ClientErrorCause.Cancelled;
    }

    private static String nicknameOf(User user) {
        if (user.getKakaoAccount() == null || user.getKakaoAccount().getProfile() == null
                || user.getKakaoAccount().getProfile().getNickname() == null) {
            return "";
        }
        return user.getKakaoAccount().getProfile().getNickname();
    }
}
