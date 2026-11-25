package com.medimate.server.drug;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medimate.server.common.Api;

@RestController
@RequestMapping("/medications")
public class MedicationController {

    private final DrugInfoService drugs;

    public MedicationController(DrugInfoService drugs) {
        this.drugs = drugs;
    }

    /** 약 상세: 품목 정보, e약은요 설명(info), 화면 버튼용 항목(sections). */
    @GetMapping("/{itemSeq}")
    Api.Response<ObjectNode> detail(@PathVariable String itemSeq) {
        return Api.ok(drugs.detail(itemSeq));
    }

    @GetMapping("/{itemSeq}/tts-script")
    Api.Response<DrugInfoService.TtsScript> tts(@PathVariable String itemSeq,
                                                @RequestParam(required = false) List<String> sections,
                                                @RequestParam(required = false) String verbosity) {
        return Api.ok(drugs.ttsScript(itemSeq, sections, verbosity));
    }
}
