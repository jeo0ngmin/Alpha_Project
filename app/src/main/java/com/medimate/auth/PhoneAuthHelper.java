package com.medimate.auth;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.FirebaseException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthOptions;
import com.google.firebase.auth.PhoneAuthProvider;

import java.util.concurrent.TimeUnit;

/**
 * 전화번호 인증 → Firebase 로그인 (README 7-4).
 * 개발 중에는 Firebase 콘솔에 등록한 테스트용 번호를 쓴다 (문자가 오지 않고 등록한 인증번호로 통과).
 */
public class PhoneAuthHelper {

    private static final String TAG = "PhoneAuth";
    private static final long TIMEOUT_SECONDS = 60;

    static final String MSG_BAD_NUMBER = "전화번호 형식이 올바르지 않아요. 010으로 시작하는 번호를 입력해 주세요.";
    static final String MSG_TOO_MANY = "요청이 너무 많아요. 잠시 후 다시 시도해 주세요.";
    static final String MSG_SEND_FAILED = "인증번호를 보내지 못했어요. 다시 시도해 주세요.";
    static final String MSG_CODE_SENT = "문자로 온 여섯 자리 숫자를 입력해 주세요.";

    public interface VerificationCallback {
        /** 인증번호가 발송됨. 사용자가 입력한 번호를 verifyCode()에 넘긴다 */
        void onCodeSent(String verificationId, String message);

        /** 번호가 자동으로 확인되어 입력 없이 로그인까지 끝남 */
        void onSignedIn(FirebaseUser user, boolean isNewUser);

        void onError(String message);
    }

    private final AuthRepository repository;

    public PhoneAuthHelper(AuthRepository repository) {
        this.repository = repository;
    }

    /** 국내 번호를 국제 형식으로 바꾼다. "010-1234-5678" → "+821012345678". 형식이 아니면 null */
    public static String toE164(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9+]", "");
        if (digits.startsWith("+82")) {
            digits = "0" + digits.substring(3);
        }
        if (!digits.matches("01[016789]\\d{7,8}")) {
            return null;
        }
        return "+82" + digits.substring(1);
    }

    /** 인증번호 발송. 테스트용 번호면 문자 없이 바로 onCodeSent가 온다. */
    public void startVerification(Activity activity, String phone, VerificationCallback callback) {
        String e164 = toE164(phone);
        if (e164 == null) {
            callback.onError(MSG_BAD_NUMBER);
            return;
        }
        PhoneAuthOptions options = PhoneAuthOptions.newBuilder(FirebaseAuth.getInstance())
                .setPhoneNumber(e164)
                .setTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .setActivity(activity)
                .setCallbacks(new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                    @Override
                    public void onCodeSent(@NonNull String verificationId,
                                           @NonNull PhoneAuthProvider.ForceResendingToken token) {
                        callback.onCodeSent(verificationId, MSG_CODE_SENT);
                    }

                    @Override
                    public void onVerificationCompleted(@NonNull PhoneAuthCredential credential) {
                        // 같은 기기로 문자가 와서 자동 확인된 경우. 입력 없이 넘어간다 (README 7-4)
                        signIn(credential, callback);
                    }

                    @Override
                    public void onVerificationFailed(@NonNull FirebaseException e) {
                        Log.e(TAG, "verifyPhoneNumber 실패", e);
                        if (e instanceof FirebaseAuthInvalidCredentialsException) {
                            callback.onError(MSG_BAD_NUMBER);
                        } else if (e instanceof FirebaseTooManyRequestsException) {
                            callback.onError(MSG_TOO_MANY);
                        } else {
                            callback.onError(MSG_SEND_FAILED);
                        }
                    }
                })
                .build();
        PhoneAuthProvider.verifyPhoneNumber(options);
    }

    /** 사용자가 입력한 인증번호로 로그인한다. */
    public void verifyCode(String verificationId, String code, AuthCallback callback) {
        repository.signInWithCredential(PhoneAuthProvider.getCredential(verificationId, code.trim()), callback);
    }

    private void signIn(PhoneAuthCredential credential, VerificationCallback callback) {
        repository.signInWithCredential(credential, new AuthCallback() {
            @Override
            public void onSuccess(FirebaseUser user, boolean isNewUser) {
                callback.onSignedIn(user, isNewUser);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }
}
