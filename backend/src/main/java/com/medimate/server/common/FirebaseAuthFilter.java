package com.medimate.server.common;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Firebase 로그인 확인. 앱이 보낸 Firebase ID 토큰(Authorization: Bearer)을 Google 공개키로 검증하고,
 * 사용자 ID(uid)를 요청에 붙인다. medimate.auth.firebase-project-id를 비우면 확인하지 않는다.
 * 서명 링크로 여는 사진(/files/images/{id}/content)과 API 문서는 로그인 없이 열린다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class FirebaseAuthFilter extends OncePerRequestFilter {

    public static final String UID = "medimate.uid";
    private static final String DEFAULT_JWKS =
            "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthFilter.class);

    private final ConfigurableJWTProcessor<SecurityContext> processor;
    private final ObjectMapper mapper;

    public FirebaseAuthFilter(MedimateProperties props, ObjectMapper mapper) throws MalformedURLException {
        this.mapper = mapper;
        MedimateProperties.Auth auth = props.auth();
        if (auth == null || !auth.enabled()) {
            processor = null;
            log.warn("로그인 확인 꺼짐: medimate.auth.firebase-project-id(FIREBASE_PROJECT_ID)가 비어 있습니다");
            return;
        }
        String jwks = auth.firebaseJwksUrl() == null || auth.firebaseJwksUrl().isBlank() ? DEFAULT_JWKS : auth.firebaseJwksUrl();
        JWKSource<SecurityContext> keys = JWKSourceBuilder.create(URI.create(jwks).toURL()).retrying(true).build();
        DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        String project = auth.firebaseProjectId().trim();
        p.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(project,
                new JWTClaimsSet.Builder().issuer("https://securetoken.google.com/" + project).build(),
                Set.of("sub", "exp", "iat")));
        processor = p;
        log.info("로그인 확인 켜짐: Firebase 프로젝트 {}", project);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (processor == null || "OPTIONS".equals(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean signedImage = "GET".equals(request.getMethod()) && path.matches("/files/images/[^/]+/content");
        boolean protectedApi = path.startsWith("/files/") || path.startsWith("/pill-identification")
                || path.startsWith("/recognition") || path.startsWith("/medications/");
        return signedImage || !protectedApi;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            reject(response, "로그인이 필요합니다.");
            return;
        }
        try {
            JWTClaimsSet claims = processor.process(header.substring(7).trim(), null);
            String uid = claims.getSubject();
            if (uid == null || uid.isBlank()) {
                throw new IllegalArgumentException("sub 없음");
            }
            request.setAttribute(UID, uid);
        } catch (Exception e) {
            // 토큰 내용은 로그에 남기지 않는다
            log.debug("토큰 거절: {}", e.getClass().getSimpleName());
            reject(response, "로그인이 만료되었거나 올바르지 않습니다.");
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), Api.error(new Api.ErrorBody("MM-AUTH-4010", message,
                "로그인이 필요합니다. 다시 로그인해 주세요.", null, false)));
    }

    /** 로그인한 사용자 ID. 로그인 확인이 꺼져 있으면 null. */
    public static String uid(HttpServletRequest request) {
        return (String) request.getAttribute(UID);
    }

    /** 사용량 제한의 기준: 로그인했으면 사용자, 아니면 접속 IP. */
    public static String clientKey(HttpServletRequest request) {
        String uid = uid(request);
        return uid != null ? "u:" + uid : "ip:" + request.getRemoteAddr();
    }
}
