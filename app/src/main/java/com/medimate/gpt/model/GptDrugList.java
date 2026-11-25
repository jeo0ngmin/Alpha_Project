package com.medimate.gpt.model;

import com.google.gson.annotations.SerializedName;

import java.util.Collections;
import java.util.List;

/** GPT가 OCR 원문에서 구조화한 결과 (README 5-5). 이름·용법·보관법만 있고 효능·주의사항은 없다. */
public class GptDrugList {

    private List<GptDrug> drugs;
    @SerializedName("common_instructions")
    private String commonInstructions;

    public List<GptDrug> getDrugs() {
        return drugs == null ? Collections.emptyList() : drugs;
    }

    public String getCommonInstructions() {
        return commonInstructions == null ? "" : commonInstructions;
    }

    public static class GptDrug {
        private String name;
        private String dosage;
        private String storage;

        public String getName() {
            return name == null ? "" : name.trim();
        }

        public String getDosage() {
            return dosage == null ? "" : dosage.trim();
        }

        public String getStorage() {
            return storage == null ? "" : storage.trim();
        }
    }
}
