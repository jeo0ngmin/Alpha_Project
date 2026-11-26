package com.medimate.net;

import java.io.IOException;

import androidx.annotation.Nullable;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 서버 요청마다 Firebase 로그인 토큰(Authorization: Bearer)을 붙인다 (서버 FirebaseAuthFilter가 확인).
 * 토큰이 만료돼 401이 오면 새 토큰을 받아 한 번만 다시 보낸다. 토큰 값은 로그에 남기지 않는다.
 * 로그인하지 않았으면(카카오 등) 토큰 없이 보내고, 서버가 확인을 켰다면 "로그인이 필요합니다"로 답한다.
 */
public final class AuthTokenInterceptor implements Interceptor {

    /** 백그라운드 스레드에서 불린다. 로그인하지 않았으면 null */
    public interface TokenSource {
        @Nullable
        String token(boolean forceRefresh) throws Exception;
    }

    private final TokenSource source;

    public AuthTokenInterceptor(TokenSource source) {
        this.source = source;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();
        String token = tokenOrNull(false);
        if (token == null) {
            return chain.proceed(original);
        }
        Response response = chain.proceed(withToken(original, token));
        if (response.code() != 401) {
            return response;
        }
        String fresh = tokenOrNull(true);
        if (fresh == null || fresh.equals(token)) {
            return response;
        }
        response.close();
        return chain.proceed(withToken(original, fresh));
    }

    @Nullable
    private String tokenOrNull(boolean forceRefresh) {
        try {
            return source.token(forceRefresh);
        } catch (Exception e) {
            // 토큰을 못 받으면 토큰 없이 보내고, 서버의 401 안내를 그대로 사용자에게 전한다
            return null;
        }
    }

    private static Request withToken(Request request, String token) {
        return request.newBuilder().header("Authorization", "Bearer " + token).build();
    }
}
