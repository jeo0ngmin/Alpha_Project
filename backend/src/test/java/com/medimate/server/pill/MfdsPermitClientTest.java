package com.medimate.server.pill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medimate.server.common.MedimateProperties;
import com.sun.net.httpserver.HttpServer;

/** 실제 응답(아세낙정 200300687, DrugPrdtPrmsnInfoService08)의 모양을 줄여 쓴다. */
class MfdsPermitClientTest {

    private static final String EE = """
            <DOC title="효능효과" type="EE">
              <SECTION title="">
                <ARTICLE title="">
                  <PARAGRAPH tagName="p" textIndent="" marginLeft=""><![CDATA[류마티스관절염, 요통]]></PARAGRAPH>
                </ARTICLE>
              </SECTION>
            </DOC>""";
    private static final String NB = """
            <DOC title="사용상의주의사항" type="NB">
              <SECTION title="">
                <ARTICLE title="1. 경고">
                  <PARAGRAPH tagName="p" textIndent="" marginLeft=""><![CDATA[1) 술을 <b>마시는</b> 사람은 상의한다.]]></PARAGRAPH>
                  <PARAGRAPH tagName="p" textIndent="" marginLeft=""><![CDATA[2) 심혈관계 위험]]></PARAGRAPH>
                </ARTICLE>
                <ARTICLE title="2. 다음 환자에는 투여하지 말 것">
                  <PARAGRAPH tagName="p" textIndent="" marginLeft=""><![CDATA[1) 소화성궤양 환자]]></PARAGRAPH>
                </ARTICLE>
              </SECTION>
            </DOC>""";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    private MfdsPermitClient client(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(status, bytes.length);
            x.getResponseBody().write(bytes);
            x.close();
        });
        server.start();
        MedimateProperties props = new MedimateProperties(null, null, null,
                new MedimateProperties.Mfds("test-key", null, null), null, null);
        return new MfdsPermitClient(props, mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    private String response(ObjectNode item) {
        ObjectNode root = mapper.createObjectNode();
        root.putObject("header").put("resultCode", "00");
        ObjectNode body = root.putObject("body");
        body.put("totalCount", item == null ? 0 : 1);
        var items = body.putArray("items");
        if (item != null) {
            items.add(item);
        }
        return root.toString();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 허가_문서에서_글을_뽑는다() throws IOException {
        ObjectNode item = mapper.createObjectNode();
        item.put("ITEM_SEQ", "200300687");
        item.put("EE_DOC_DATA", EE);
        item.put("UD_DOC_DATA", "None");
        item.put("NB_DOC_DATA", NB);
        item.put("STORAGE_METHOD", "기밀용기, 실온보관(1-30℃)");

        var info = client(200, response(item)).fetch("200300687");

        assertThat(info).isPresent();
        assertThat(info.get().efficacy()).isEqualTo("류마티스관절염, 요통");
        assertThat(info.get().usage()).isNull();
        assertThat(info.get().precaution()).isEqualTo(
                "1. 경고\n1) 술을 마시는 사람은 상의한다.\n2) 심혈관계 위험\n2. 다음 환자에는 투여하지 말 것\n1) 소화성궤양 환자");
        assertThat(info.get().storage()).isEqualTo("기밀용기, 실온보관(1-30℃)");
    }

    /** 2026-11-26에 받은 실제 응답(아세낙정, 전문의약품) 그대로 */
    @Test
    void 실제_응답에서_처방약_설명을_채운다() throws IOException {
        String body;
        try (var in = getClass().getResourceAsStream("/mfds/permit_200300687.json")) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        var info = client(200, body).fetch("200300687");

        assertThat(info).isPresent();
        assertThat(info.get().efficacy()).startsWith("류마티스관절염, 강직척추염");
        assertThat(info.get().usage()).contains("1회 100 mg을 12시간마다 1일 2회 복용한다.");
        assertThat(info.get().precaution()).startsWith("1. 경고\n1) 매일 세잔 이상");
        assertThat(info.get().precaution()).doesNotContain("<", "CDATA");
        assertThat(info.get().storage()).isEqualTo("기밀용기, 실온보관(1-30℃)");
    }

    @Test
    void 허가정보에_없는_약은_빈_값이다() throws IOException {
        assertThat(client(200, response(null)).fetch("1")).isEmpty();
    }

    @Test
    void 호출이_실패하면_일시_장애로_알린다() throws IOException {
        MfdsPermitClient failing = client(500, "x");
        assertThatThrownBy(() -> failing.fetch("1")).isInstanceOf(MfdsClient.UnavailableException.class)
                .hasMessageNotContaining("test-key");
    }
}
