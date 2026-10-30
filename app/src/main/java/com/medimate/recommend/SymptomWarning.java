package com.medimate.recommend;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * 병원 진료 안내 표(assets/symptom_warnings.json) 한 줄 (README 8-7).
 * 질환명이 들어간 안내 문구는 AI가 만들지 않고 이 표의 고정 문구만 쓴다. 출처가 없으면 읽을 때 버린다.
 */
public class SymptomWarning {

    private String id;        // 분류기가 고르는 이름
    private String symptoms;  // 이 안내가 다루는 증상 (분류기에 범위로 준다)
    private String message;   // 답변 끝에 붙이는 고정 문구
    private String source;    // 질환명의 근거 출처

    public String getId() {
        return id == null ? "" : id;
    }

    public String getSymptoms() {
        return symptoms == null ? "" : symptoms;
    }

    public String getMessage() {
        return message == null ? "" : message;
    }

    public String getSource() {
        return source == null ? "" : source;
    }

    /** 표를 읽고, 빈 칸이 있는 줄은 버린다 */
    public static List<SymptomWarning> load(Reader json) {
        List<SymptomWarning> all = new Gson().fromJson(json, new TypeToken<List<SymptomWarning>>() {}.getType());
        List<SymptomWarning> valid = new ArrayList<>();
        if (all != null) {
            for (SymptomWarning w : all) {
                if (!w.getId().isEmpty() && !w.getSymptoms().isEmpty()
                        && !w.getMessage().isEmpty() && !w.getSource().isEmpty()) {
                    valid.add(w);
                }
            }
        }
        return valid;
    }
}
