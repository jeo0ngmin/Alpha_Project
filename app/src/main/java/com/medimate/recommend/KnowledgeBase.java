package com.medimate.recommend;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 시작 자료의 근거 규칙·출처·용어를 모아 찾는다 (README 8-8). 순수 자바, 네트워크 없음.
 * 규칙에 걸리지 않았다는 것은 "안전"이 아니라 "모름"이다 (정책 P03). 판단은 이 표로만 하고 AI 기억으로 하지 않는다.
 */
public class KnowledgeBase {

    /** 답변에 남기는 지식 버전 (시작 자료 response_contract의 knowledge_version) */
    public static final String VERSION = "starter-2026-10-30";

    private final List<KnowledgeRule> rules;
    private final Map<String, Source> sources;
    private final Map<String, String> terms;

    static class Source {
        @SerializedName("source_id")
        String id;
        @SerializedName("publisher")
        String publisher;
        @SerializedName("title")
        String title;
    }

    KnowledgeBase(List<KnowledgeRule> rules, Map<String, Source> sources, Map<String, String> terms) {
        this.rules = new ArrayList<>();
        for (KnowledgeRule rule : rules) {
            // 출처가 없는 규칙은 쓰지 않는다 (README 8-1)
            if (!rule.getSourceIds().isEmpty() && !rule.getSummary().isEmpty()) {
                this.rules.add(rule);
            }
        }
        this.sources = sources;
        this.terms = terms;
    }

    /** 릴리스 빌드처럼 규칙을 쓰지 않을 때 */
    public static KnowledgeBase empty() {
        return new KnowledgeBase(new ArrayList<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    /**
     * @param rulesJsonl  knowledge_rules.jsonl (한 줄에 규칙 하나)
     * @param sourcesJson knowledge_sources.json
     * @param termsJson   knowledge_terms.json (대상 id → 한국어 이름)
     */
    public static KnowledgeBase load(Reader rulesJsonl, Reader sourcesJson, Reader termsJson) throws IOException {
        Gson gson = new Gson();
        List<KnowledgeRule> rules = new ArrayList<>();
        BufferedReader lines = new BufferedReader(rulesJsonl);
        for (String line = lines.readLine(); line != null; line = lines.readLine()) {
            if (!line.trim().isEmpty()) {
                try {
                    rules.add(gson.fromJson(line, KnowledgeRule.class));
                } catch (JsonParseException e) {
                    throw new IOException("knowledge_rules.jsonl 파싱 실패", e);
                }
            }
        }
        List<Source> sourceList = gson.fromJson(sourcesJson, new TypeToken<List<Source>>() {}.getType());
        Map<String, Source> sources = new LinkedHashMap<>();
        if (sourceList != null) {
            for (Source s : sourceList) {
                sources.put(s.id, s);
            }
        }
        Map<String, String> terms = gson.fromJson(termsJson, new TypeToken<Map<String, String>>() {}.getType());
        return new KnowledgeBase(rules, sources, terms == null ? new LinkedHashMap<>() : terms);
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** 응급·진료 분류 id → 그 상황 설명 (분류기에 준다) */
    public Map<String, String> triageScopes() {
        Map<String, String> scopes = new LinkedHashMap<>();
        for (KnowledgeRule rule : rules) {
            if (rule.isTriage()) {
                for (String id : rule.getTargetIds()) {
                    scopes.put(id, rule.getSummary());
                }
            }
        }
        return scopes;
    }

    /** 사용자가 말한 약·질환 등을 연결할 대상 id → 한국어 이름 (분류기에 준다) */
    public Map<String, String> linkTargets() {
        Map<String, String> targets = new LinkedHashMap<>();
        for (KnowledgeRule rule : rules) {
            if (!rule.isTriage()) {
                for (String id : rule.getTargetIds()) {
                    targets.put(id, termName(id));
                }
            }
        }
        return targets;
    }

    /** 근거 규칙에 나오는 성분 id (AI 추천 성분을 이 id에 맞춰 상호작용을 확인한다) */
    public List<String> ingredientIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (KnowledgeRule rule : rules) {
            if (!rule.getIngredientId().isEmpty()) {
                ids.add(rule.getIngredientId());
            }
        }
        return new ArrayList<>(ids);
    }

    /** 대상 id의 한국어 이름. 이름표에 없으면 id 그대로 */
    public String termName(String id) {
        String name = terms.get(id);
        return name == null ? id : name;
    }

    /** 응급·진료 분류 규칙 중 해당 id에 걸리는 것 */
    public List<KnowledgeRule> triageFor(Collection<String> symptomIds) {
        List<KnowledgeRule> matched = new ArrayList<>();
        for (KnowledgeRule rule : rules) {
            if (rule.isTriage() && intersects(rule.getTargetIds(), symptomIds)) {
                matched.add(rule);
            }
        }
        return matched;
    }

    /** 후보 성분과 사용자 상태(확인된 대상 id)에 걸리는 상호작용·질환 규칙 */
    public List<KnowledgeRule> matching(Collection<String> ingredientIds, Collection<String> targetIds) {
        List<KnowledgeRule> matched = new ArrayList<>();
        for (KnowledgeRule rule : rules) {
            if (!rule.isTriage() && ingredientIds.contains(rule.getIngredientId())
                    && intersects(rule.getTargetIds(), targetIds)) {
                matched.add(rule);
            }
        }
        return matched;
    }

    /** 후보 성분에 대해 규칙이 걸릴 수 있는 대상 id. 이 id로 연결된 것만 사용자에게 확인받는다 */
    public Set<String> relevantTargets(Collection<String> ingredientIds) {
        Set<String> targets = new LinkedHashSet<>();
        for (KnowledgeRule rule : rules) {
            if (!rule.isTriage() && ingredientIds.contains(rule.getIngredientId())) {
                targets.addAll(rule.getTargetIds());
            }
        }
        return targets;
    }

    /** "NIH ODS 「Magnesium — Health Professional」" 처럼 출처 이름을 잇는다 */
    public String sourceText(KnowledgeRule rule) {
        List<String> names = new ArrayList<>();
        for (String id : rule.getSourceIds()) {
            Source s = sources.get(id);
            names.add(s == null ? id : s.publisher + " 「" + s.title + "」");
        }
        return String.join(", ", names);
    }

    private static boolean intersects(Collection<String> a, Collection<String> b) {
        for (String x : a) {
            if (b.contains(x)) {
                return true;
            }
        }
        return false;
    }
}
