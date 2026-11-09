package com.medimate.server.pill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medimate.server.common.MedimateProperties;
import com.sun.net.httpserver.HttpServer;

class MfdsClientTest {

    private HttpServer server;

    private MfdsClient client(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(status, bytes.length);
            x.getResponseBody().write(bytes);
            x.close();
        });
        server.start();
        MedimateProperties props = new MedimateProperties(null, null, null, new MedimateProperties.Mfds("test-key", null, null), null);
        return new MfdsClient(props, new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 설명을_읽고_HTML을_지운다() throws IOException {
        var info = client(200, """
                {"body":{"items":[{"efcyQesitm":"<p>두통에\\n 씁니다.</p>","useMethodQesitm":"하루 3회","atpnWarnQesitm":null,
                 "atpnQesitm":"주의","intrcQesitm":"","seQesitm":"발진","depositMethodQesitm":"실온"}]}}""").fetch("1");

        assertThat(info).isPresent();
        assertThat(info.get().efficacy()).isEqualTo("두통에 씁니다.");
        assertThat(info.get().warning()).isNull();
        assertThat(info.get().interaction()).isNull();
    }

    @Test
    void e약은요에_없는_약은_빈_값이다() throws IOException {
        assertThat(client(200, "{\"body\":{\"items\":[],\"totalCount\":0}}").fetch("1")).isEmpty();
    }

    @Test
    void 호출이_실패하면_일시_장애로_알린다() throws IOException {
        MfdsClient failing = client(500, "x");
        assertThatThrownBy(() -> failing.fetch("1")).isInstanceOf(MfdsClient.UnavailableException.class)
                .hasMessageNotContaining("test-key");
    }

    @Test
    void 키_파일을_읽는다(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("key");
        Files.writeString(file, "# 설명\nMFDS_KEY=\"abc==\"\n");
        assertThat(MfdsClient.resolveKey(new MedimateProperties.Mfds("", file.toString(), null))).isEqualTo("abc==");
        Files.writeString(file, "plainKey==\n");
        assertThat(MfdsClient.resolveKey(new MedimateProperties.Mfds(null, file.toString(), null))).isEqualTo("plainKey==");
        assertThat(MfdsClient.resolveKey(new MedimateProperties.Mfds(" env ", file.toString(), null))).isEqualTo("env");
        assertThat(MfdsClient.resolveKey(new MedimateProperties.Mfds("", dir.resolve("없음").toString(), null))).isNull();
    }
}
