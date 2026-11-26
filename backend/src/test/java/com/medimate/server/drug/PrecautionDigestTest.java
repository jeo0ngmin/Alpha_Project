package com.medimate.server.drug;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medimate.server.pill.MfdsPermitClient;

class PrecautionDigestTest {

    /** 2026-11-26에 받은 실제 허가정보 응답(아세낙정)의 사용상의 주의사항 */
    private static String realPrecaution() throws IOException {
        try (var in = PrecautionDigestTest.class.getResourceAsStream("/mfds/permit_200300687.json")) {
            var item = new ObjectMapper().readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .path("body").path("items").get(0);
            return MfdsPermitClient.docText(item.path("NB_DOC_DATA").asText());
        }
    }

    @Test
    void 실제_주의사항에서_경고_금기_고령자만_고른다() throws IOException {
        String all = realPrecaution();
        String digest = PrecautionDigest.keyPoints(all);

        assertThat(digest).startsWith("1. 경고\n1) 매일 세잔 이상");
        assertThat(digest).contains("2. 다음 환자(경우)에는 투여하지 말 것.", "9. 고령자에 대한 투여");
        assertThat(digest).doesNotContain("4. 이상반응", "6. 상호작용");
        // 고르기만 하고 글은 바꾸지 않는다: 모든 줄이 원문에 그대로 있다
        for (String line : digest.split("\n")) {
            assertThat(all).contains(line);
        }
        assertThat(digest.length()).isLessThan(all.length() / 3);
    }

    @Test
    void 요점_표시가_없는_항목은_앞_문단만_쓴다() {
        String text = "1. 경고\n첫 문단\n둘째 문단\n셋째 문단\n2. 이상반응\n" + "가".repeat(500);
        assertThat(PrecautionDigest.keyPoints(text)).isEqualTo("1. 경고\n첫 문단\n둘째 문단");
    }

    @Test
    void 고를_항목이_없거나_짧으면_null이다() {
        assertThat(PrecautionDigest.keyPoints("1. 이상반응\n두드러기")).isNull();
        assertThat(PrecautionDigest.keyPoints("1. 경고\n1) 술을 마시지 말 것")).isNull();
        assertThat(PrecautionDigest.keyPoints(null)).isNull();
    }

    @Test
    void 허가정보_주의사항_버튼은_고른_글과_전체를_함께_준다() throws IOException {
        String all = realPrecaution();
        var info = new DrugInfoService.Info(null, null, null, all, null, null, null);
        var caution = DrugInfoService.sections(new DrugInfoService.Lookup("FOUND", info, null,
                DrugInfoService.SOURCE_PERMIT)).stream().filter(s -> s.key().equals("CAUTION")).findFirst().orElseThrow();

        assertThat(caution.text()).startsWith("1. 경고");
        assertThat(caution.fullText()).isEqualTo(all);
        assertThat(caution.text().length()).isLessThan(all.length());
    }

    @Test
    void e약은요_주의사항은_그대로_준다() {
        var info = new DrugInfoService.Info(null, null, "경고 내용", "주의 내용", null, null, null);
        var caution = DrugInfoService.sections(new DrugInfoService.Lookup("FOUND", info, null)).stream()
                .filter(s -> s.key().equals("CAUTION")).findFirst().orElseThrow();
        assertThat(caution.text()).isEqualTo("경고 내용 주의 내용");
        assertThat(caution.fullText()).isNull();
    }
}
