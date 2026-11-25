package com.medimate.auth;

import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/** 회원가입 입력 검증 (SignupActivity). 문제가 있으면 사용자에게 읽어 줄 문장, 없으면 null. */
public final class SignupValidator {

    public static final int MIN_PASSWORD = 8;

    static final String MSG_EMAIL_EMPTY = "이메일을 입력해 주세요.";
    static final String MSG_EMAIL_FORMAT = "이메일 형식이 올바르지 않아요. 예: medimate@example.com";
    static final String MSG_PASSWORD_SHORT = "비밀번호는 " + MIN_PASSWORD + "자 이상으로 입력해 주세요.";
    static final String MSG_PASSWORD_MIX = "비밀번호에 영문과 숫자를 함께 넣어 주세요.";
    static final String MSG_PASSWORD_MISMATCH = "비밀번호 재입력이 일치하지 않아요.";
    static final String MSG_TERMS = "서비스 이용약관과 개인정보 처리방침에 동의해 주세요.";

    // 단위 테스트에서도 돌도록 android.util.Patterns 대신 간단한 형식만 본다. 실제 확인은 Firebase가 한다
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");

    private SignupValidator() {
    }

    @Nullable
    public static String check(String email, String password, String confirm, boolean termsAgreed) {
        String e = email == null ? "" : email.trim();
        if (e.isEmpty()) {
            return MSG_EMAIL_EMPTY;
        }
        if (!EMAIL.matcher(e).matches()) {
            return MSG_EMAIL_FORMAT;
        }
        String p = password == null ? "" : password;
        if (p.length() < MIN_PASSWORD) {
            return MSG_PASSWORD_SHORT;
        }
        if (!p.matches(".*[A-Za-z].*") || !p.matches(".*\\d.*")) {
            return MSG_PASSWORD_MIX;
        }
        if (!p.equals(confirm)) {
            return MSG_PASSWORD_MISMATCH;
        }
        if (!termsAgreed) {
            return MSG_TERMS;
        }
        return null;
    }
}
