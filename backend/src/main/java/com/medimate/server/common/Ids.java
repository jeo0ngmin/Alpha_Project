package com.medimate.server.common;

import java.security.SecureRandom;

/** ULID: 시간순으로 정렬되는 26자 ID. 작업·파일 ID에 접두사와 함께 쓴다 (pid_, f_). */
public final class Ids {

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String ulid() {
        long time = System.currentTimeMillis();
        char[] out = new char[26];
        for (int i = 9; i >= 0; i--) {
            out[i] = CROCKFORD[(int) (time & 31)];
            time >>>= 5;
        }
        byte[] rand = new byte[16];
        RANDOM.nextBytes(rand);
        for (int i = 10; i < 26; i++) {
            out[i] = CROCKFORD[rand[i - 10] & 31];
        }
        return new String(out);
    }

    public static String prefixed(String prefix) {
        return prefix + ulid();
    }
}
