package com.medimate.recommend;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 고민 → 추천 성분 (README 8-1). 규칙 표로만 정하고, 순수 자바라 네트워크가 없다.
 * 출처가 비어 있는 규칙은 읽을 때 걸러 낸다.
 */
public class RecommendEngine {

    private final List<RecommendRule> rules;

    public RecommendEngine(List<RecommendRule> rules) {
        this.rules = new ArrayList<>();
        for (RecommendRule rule : rules) {
            if (!rule.getConcern().isEmpty() && !rule.getIngredient().isEmpty() && !rule.getSource().isEmpty()) {
                this.rules.add(rule);
            }
        }
    }

    /** assets/supplement_rules.json 을 읽어 만든다 */
    public static RecommendEngine load(Reader json) {
        List<RecommendRule> rules = new Gson().fromJson(json, new TypeToken<List<RecommendRule>>() {}.getType());
        return new RecommendEngine(rules == null ? new ArrayList<>() : rules);
    }

    /** 규칙 표에 있는 고민 이름 목록 (중복 없이, 표 순서대로). 분류기에 보기로 준다 */
    public List<String> concerns() {
        Set<String> names = new LinkedHashSet<>();
        for (RecommendRule rule : rules) {
            names.add(rule.getConcern());
        }
        return new ArrayList<>(names);
    }

    /** 고민 목록에 해당하는 규칙을 표 순서대로 돌려준다. 없으면 빈 목록 */
    public List<RecommendRule> recommend(Collection<String> concerns) {
        List<RecommendRule> matched = new ArrayList<>();
        for (RecommendRule rule : rules) {
            if (concerns.contains(rule.getConcern())) {
                matched.add(rule);
            }
        }
        return matched;
    }
}
