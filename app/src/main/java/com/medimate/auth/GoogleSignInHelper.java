package com.medimate.auth;

import android.app.Activity;
import android.util.Log;

import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.firebase.auth.GoogleAuthProvider;
import com.medimate.R;

import java.util.concurrent.Executor;

/**
 * 구글 → Firebase 로그인 (README 7-3). Credential Manager 방식.
 * 서버 클라이언트 ID는 google-services.json이 만드는 default_web_client_id를 쓴다. 코드에 적지 않는다.
 */
public class GoogleSignInHelper {

    private static final String TAG = "GoogleSignIn";

    static final String MSG_CANCELLED = "로그인을 취소했어요.";
    static final String MSG_NO_ACCOUNT = "이 기기에 구글 계정이 없어요. 기기 설정에서 계정을 추가해 주세요.";
    static final String MSG_FAILED = "구글 로그인에 실패했어요. 다시 시도해 주세요.";

    private final AuthRepository repository;

    public GoogleSignInHelper(AuthRepository repository) {
        this.repository = repository;
    }

    /** 계정 선택 창을 띄우고, 고른 계정의 ID 토큰으로 Firebase에 로그인한다. */
    public void signIn(Activity activity, AuthCallback callback) {
        GetGoogleIdOption option = new GetGoogleIdOption.Builder()
                .setServerClientId(activity.getString(R.string.default_web_client_id))
                .setFilterByAuthorizedAccounts(false)   // 처음 쓰는 계정도 고를 수 있게
                .build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build();

        Executor mainExecutor = activity.getMainExecutor();
        CredentialManager.create(activity).getCredentialAsync(activity, request, null, mainExecutor,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        String idToken = extractIdToken(response.getCredential());
                        if (idToken == null) {
                            Log.e(TAG, "구글 ID 토큰이 아닌 자격 증명이 돌아옴");
                            callback.onError(MSG_FAILED);
                            return;
                        }
                        repository.signInWithCredential(
                                GoogleAuthProvider.getCredential(idToken, null), callback);
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        if (e instanceof GetCredentialCancellationException) {
                            callback.onError(MSG_CANCELLED);
                        } else if (e instanceof NoCredentialException) {
                            callback.onError(MSG_NO_ACCOUNT);
                        } else {
                            Log.e(TAG, "Credential Manager 실패", e);
                            callback.onError(MSG_FAILED);
                        }
                    }
                });
    }

    private static String extractIdToken(Credential credential) {
        if (credential instanceof CustomCredential
                && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
            return GoogleIdTokenCredential.createFrom(credential.getData()).getIdToken();
        }
        return null;
    }
}
