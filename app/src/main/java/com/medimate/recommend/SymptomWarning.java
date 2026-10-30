package com.medimate.recommend;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * 병원 진료·응급 안내 표(assets/symptom_warnings.json) 한 줄 (README 8-7).
 * 응급 줄은 전문가 검토 전 시작 자료와 달리 공식 출처로 직접 확인한 것이라 릴리스 빌드에서도 쓴다.
 * 질환명이 들어간 안내 문구는 AI가 만들지 않고 이 표의 고정 문구만 쓴다. 출처가 없으면 읽을 때 버린다.
 */
public class SymptomWarning {

    public static final String LEVEL_EMERGENCY = "emergency";

    private String id;        // 분류기가 고르는 이름
    private String level;     // "emergency"면 추천 없이 바로 119 안내, 아니면 답변 끝에 병원 안내 (README 8-7)
    private String symptoms;  // 이 안내가 다루는 증상 (분류기에 범위로 준다)
    private String message;   // 답변 끝에 붙이는 고정 문구
    private String source;    // 질환명의 근거 출처

    public String getId() {
        return id == null ? "" : id;
    }

    /** 응급이면 모든 빌드에서 추천 없이 바로 안내한다 */
    public boolean isEmergency() {
        return LEVEL_EMERGENCY.equals(level);
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
