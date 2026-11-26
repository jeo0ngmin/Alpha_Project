package com.medimate.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public class AuthTokenInterceptorTest {

    private MockWebServer server;
    private final List<Boolean> refreshCalls = new ArrayList<>();

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private Response call(AuthTokenInterceptor.TokenSource source) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(new AuthTokenInterceptor(source)).build();
        return client.newCall(new Request.Builder().url(server.url("/pill-identification")).build()).execute();
    }

    @Test
    public void attachesFirebaseToken() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        try (Response r = call(refresh -> "token-1")) {
            assertEquals(200, r.code());
        }
        RecordedRequest request = server.takeRequest();
        assertEquals("Bearer token-1", request.getHeader("Authorization"));
    }

    @Test
    public void expiredTokenIsRefreshedOnce() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(200));
        try (Response r = call(refresh -> {
            refreshCalls.add(refresh);
            return refresh ? "token-new" : "token-old";
        })) {
            assertEquals(200, r.code());
        }
        assertEquals("Bearer token-old", server.takeRequest().getHeader("Authorization"));
        assertEquals("Bearer token-new", server.takeRequest().getHeader("Authorization"));
        assertEquals(2, refreshCalls.size());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    public void notSignedInSendsWithoutToken() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        try (Response r = call(refresh -> null)) {
            assertEquals(401, r.code());
        }
        assertNull(server.takeRequest().getHeader("Authorization"));
        assertEquals(1, server.getRequestCount());
    }
}
