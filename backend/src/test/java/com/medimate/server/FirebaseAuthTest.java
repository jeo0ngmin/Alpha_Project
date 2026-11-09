package com.medimate.server;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/** Firebase 프로젝트 ID를 설정하면 알약·약봉투 API는 유효한 Firebase ID 토큰이 있어야 쓸 수 있다. */
@SpringBootTest
@AutoConfigureMockMvc
class FirebaseAuthTest {

    private static final String PROJECT = "medimate-test";
    private static final RSAKey KEY;
    private static final RSAKey OTHER_KEY;
    private static final HttpServer JWKS;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("k1").generate();
            OTHER_KEY = new RSAKeyGenerator(2048).keyID("k1").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (JOSEException | IOException e) {
            throw new IllegalStateException(e);
        }
        JWKS.createContext("/keys", x -> {
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        JWKS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("medimate.auth.firebase-project-id", () -> PROJECT);
        r.add("medimate.auth.firebase-jwks-url", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/keys");
        r.add("medimate.pill.base-url", () -> "http://127.0.0.1:1");
    }

    @AfterAll
    static void stop() {
        JWKS.stop(0);
    }

    private static String token(RSAKey key, String audience, String issuer, String uid, Instant expires) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().audience(audience).issuer(issuer).subject(uid)
                .issueTime(Date.from(Instant.now().minusSeconds(60))).expirationTime(Date.from(expires)).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return "Bearer " + jwt.serialize();
    }

    private static String valid(String uid) throws JOSEException {
        return token(KEY, PROJECT, "https://securetoken.google.com/" + PROJECT, uid, Instant.now().plusSeconds(600));
    }

    @Autowired
    MockMvc mvc;

    @Test
    void 토큰이_없거나_틀리면_401() throws Exception {
        mvc.perform(get("/pill-identification/rcg_x")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("MM-AUTH-4010"))
                .andExpect(jsonPath("$.error.ttsMessage").isNotEmpty());
        mvc.perform(get("/recognition/jobs/rcg_x")).andExpect(status().isUnauthorized());
        mvc.perform(post("/files/images")).andExpect(status().isUnauthorized());

        String issuer = "https://securetoken.google.com/" + PROJECT;
        mvc.perform(get("/recognition/jobs/rcg_x").header("Authorization",
                token(OTHER_KEY, PROJECT, issuer, "u1", Instant.now().plusSeconds(600)))).andExpect(status().isUnauthorized());
        mvc.perform(get("/recognition/jobs/rcg_x").header("Authorization",
                token(KEY, "other-project", issuer, "u1", Instant.now().plusSeconds(600)))).andExpect(status().isUnauthorized());
        mvc.perform(get("/recognition/jobs/rcg_x").header("Authorization",
                token(KEY, PROJECT, "https://evil.example", "u1", Instant.now().plusSeconds(600)))).andExpect(status().isUnauthorized());
        mvc.perform(get("/recognition/jobs/rcg_x").header("Authorization",
                token(KEY, PROJECT, issuer, "u1", Instant.now().minusSeconds(600)))).andExpect(status().isUnauthorized());
        mvc.perform(get("/recognition/jobs/rcg_x").header("Authorization", "Bearer 엉터리")).andExpect(status().isUnauthorized());
    }

    @Test
    void 올바른_토큰이면_통과한다() throws Exception {
        mvc.perform(get("/recognition/jobs/rcg_NOPE").header("Authorization", valid("u1"))).andExpect(status().isNotFound());
        mvc.perform(post("/recognition/medication-bags").header("Authorization", valid("u1"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void 일정_API는_없다() throws Exception {
        // 복약 일정은 앱이 Firestore에 저장한다. 서버에는 일정 API가 없다
        mvc.perform(get("/medication-plans").header("Authorization", valid("alice"))).andExpect(status().isNotFound());
    }

    @Test
    void 문서와_서명_링크는_토큰_없이_열린다() throws Exception {
        mvc.perform(get("/openapi.json")).andExpect(status().isOk());
        mvc.perform(get("/files/images/f_00000000000000000000000000/content").param("exp", "1").param("sig", "x"))
                .andExpect(status().isForbidden()); // 로그인이 아니라 서명으로 막힌다
    }
}
