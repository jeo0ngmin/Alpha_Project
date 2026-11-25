package com.medimate.server.drug;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DrugInfoWordingTest {

    @Test
    void 옛_표기와_오타를_고친다() {
        DrugInfoService.Info info = new DrugInfoService.Info(null, null, null, null, null,
                "쇽 증상(호흡곤란 등), 천공(뚦림) 등이 나타나는 경우", null);
        assertThat(info.sideEffect()).isEqualTo("쇼크 증상(호흡곤란 등), 천공(뚫림) 등이 나타나는 경우");
        assertThat(info.efficacy()).isNull();
    }

    @Test
    void 음성에도_고친_글을_쓴다() {
        DrugInfoService.Info info = new DrugInfoService.Info(null, null, null, null, null, "쇽 증상", null);
        DrugInfoService.Section s = DrugInfoService.sections(new DrugInfoService.Lookup("FOUND", info, null)).stream()
                .filter(x -> x.key().equals("SIDE_EFFECT")).findFirst().orElseThrow();
        assertThat(s.text()).isEqualTo("쇼크 증상");
        assertThat(s.ttsText()).contains("쇼크 증상").doesNotContain("쇽");
    }
}
