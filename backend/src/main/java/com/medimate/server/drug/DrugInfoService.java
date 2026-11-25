package com.medimate.server.drug;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medimate.server.common.ApiException;
import com.medimate.server.common.Times;
import com.medimate.server.pill.MfdsClient;
import com.medimate.server.pill.PillClient;
import com.medimate.server.recognition.BagSpeech;
import com.medimate.server.speech.SpeechFormatter;

/**
 * 약 상세 정보: 식약처 낱알식별(추론 서버) + e약은요 설명. e약은요 결과는 DB에 30일간 보관해 호출 횟수를 아낀다.
 * 앱의 "약 상세 정보" 화면은 sections(약 설명, 주의사항, 복용방법, 보관방법 …)를 그대로 버튼으로 쓰면 된다.
 */
@Service
public class DrugInfoService {

    public record Info(String efficacy, String useMethod, String warning, String precaution, String interaction,
                       String sideEffect, String depositMethod) {
        /** 새로 받은 값과 DB에 보관된 값 모두 원문의 옛 표기·오타를 고쳐서 쓴다. */
        public Info {
            efficacy = wording(efficacy);
            useMethod = wording(useMethod);
            warning = wording(warning);
            precaution = wording(precaution);
            interaction = wording(interaction);
            sideEffect = wording(sideEffect);
            depositMethod = wording(depositMethod);
        }
    }

    /** e약은요 원문의 옛 표기(쇽 → 쇼크)와 오타(뚦림 → 뚫림)를 고친다. 화면과 음성이 같은 글을 쓴다. */
    static String wording(String text) {
        return text == null ? null : text.replace("쇽", "쇼크").replace("뚦", "뚫");
    }

    /** status: FOUND(설명 있음) | NOT_IN_MFDS(e약은요에 없는 약) | UNAVAILABLE(일시 장애) | NOT_CONFIGURED(키 없음) */
    public record Lookup(String status, Info info, OffsetDateTime fetchedAt) {
    }

    /** 화면 버튼 하나. text는 화면에, ttsText는 음성에 쓴다. */
    public record Section(String key, String title, String text, String ttsText) {
    }

    public record Segment(String section, int order, String text, boolean emphasis, int pauseAfterMs) {
    }

    public record TtsScript(String itemSeq, String verbosity, int estimatedDurationSeconds, List<Segment> segments,
                            String fullText) {
    }

    private static final Logger log = LoggerFactory.getLogger(DrugInfoService.class);
    private static final Duration CACHE_LIFE = Duration.ofDays(30);
    /** SIMPLE 낭독에서 항목마다 읽는 최대 글자 수. 한 항목이 너무 길어지지 않게 한다. */
    private static final int SIMPLE_MAX_CHARS = 90;
    private static final String NO_INFO = "이 약의 자세한 설명은 공공데이터에 없습니다. 약사에게 확인해 주세요.";

    private final JdbcClient jdbc;
    private final MfdsClient mfds;
    private final PillClient pill;
    private final ObjectMapper mapper;

    public DrugInfoService(JdbcClient jdbc, MfdsClient mfds, PillClient pill, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mfds = mfds;
        this.pill = pill;
        this.mapper = mapper;
    }

    // ── e약은요 (캐시) ─────────────────────────────────────────────

    private record Cached(boolean found, Info info, OffsetDateTime fetchedAt) {
    }

    public Lookup easyInfo(String itemSeq) {
        Optional<Cached> cached = jdbc.sql("""
                SELECT found, efficacy, use_method, warning, precaution, interaction, side_effect, deposit_method, fetched_at
                FROM drug_info WHERE item_seq=?""").param(itemSeq).query((rs, i) -> new Cached(rs.getBoolean(1),
                new Info(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8)), rs.getObject(9, OffsetDateTime.class))).optional();
        boolean fresh = cached.isPresent() && cached.get().fetchedAt().toInstant().isAfter(Times.now().minus(CACHE_LIFE));
        if (fresh) {
            return toLookup(cached.get());
        }
        if (!mfds.isConfigured()) {
            return cached.map(DrugInfoService::toLookup).orElse(new Lookup("NOT_CONFIGURED", null, null));
        }
        try {
            Optional<MfdsClient.EasyInfo> fetched = mfds.fetch(itemSeq);
            Info info = fetched.map(e -> new Info(e.efficacy(), e.useMethod(), e.warning(), e.precaution(),
                    e.interaction(), e.sideEffect(), e.depositMethod())).orElse(null);
            store(itemSeq, info);
            return new Lookup(info == null ? "NOT_IN_MFDS" : "FOUND", info, Times.nowOffset());
        } catch (MfdsClient.UnavailableException e) {
            log.warn("e약은요 조회 실패: {}", e.getMessage());
            // 다시 묻다가 실패해도 예전 값이 있으면 그것을 쓴다
            return cached.map(DrugInfoService::toLookup).orElse(new Lookup("UNAVAILABLE", null, null));
        }
    }

    private static Lookup toLookup(Cached c) {
        return new Lookup(c.found() ? "FOUND" : "NOT_IN_MFDS", c.found() ? c.info() : null, c.fetchedAt());
    }

    private void store(String itemSeq, Info i) {
        Object[] v = i == null
                ? new Object[]{false, null, null, null, null, null, null, null, Times.nowOffset(), itemSeq}
                : new Object[]{true, i.efficacy(), i.useMethod(), i.warning(), i.precaution(), i.interaction(),
                i.sideEffect(), i.depositMethod(), Times.nowOffset(), itemSeq};
        int updated = jdbc.sql("""
                UPDATE drug_info SET found=?, efficacy=?, use_method=?, warning=?, precaution=?, interaction=?,
                  side_effect=?, deposit_method=?, fetched_at=? WHERE item_seq=?""").params(v).update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO drug_info (found, efficacy, use_method, warning, precaution, interaction, side_effect,
                      deposit_method, fetched_at, item_seq) VALUES (?,?,?,?,?,?,?,?,?,?)""").params(v).update();
        }
    }

    /** 품목기준코드가 없으면(식약처 품목과 연결 안 됨) 안내 문장만 있는 항목을 돌려준다. */
    public Lookup lookupOrUnmatched(String itemSeq) {
        return itemSeq == null ? new Lookup("NOT_MATCHED", null, null) : easyInfo(itemSeq);
    }

    // ── 약 상세 ───────────────────────────────────────────────────

    /** 추론 서버의 품목 정보에 e약은요 설명과 화면용 항목을 붙인다. */
    public ObjectNode detail(String itemSeq) {
        ObjectNode item = item(itemSeq);
        Lookup lookup = easyInfo(itemSeq);
        if (lookup.info() != null) {
            item.set("info", mapper.valueToTree(lookup.info()));
            ObjectNode source = item.putObject("source");
            source.put("provider", "MFDS");
            source.put("dataset", "DrbEasyDrugInfoService");
            source.put("fetchedAt", lookup.fetchedAt().toString());
        }
        item.put("infoStatus", lookup.status());
        item.set("sections", mapper.valueToTree(sections(lookup)));
        return item;
    }

    /** 약봉투 확정·알약 선택 응답에 e약은요 설명만 붙일 때 쓴다. 실패해도 막지 않는다. */
    public void attachInfo(ObjectNode medication, String itemSeq) {
        Lookup lookup = easyInfo(itemSeq);
        if (lookup.info() != null) {
            medication.set("info", mapper.valueToTree(lookup.info()));
            ObjectNode source = medication.putObject("source");
            source.put("provider", "MFDS");
            source.put("dataset", "DrbEasyDrugInfoService");
            source.put("fetchedAt", lookup.fetchedAt().toString());
        }
    }

    private ObjectNode item(String itemSeq) {
        if (!itemSeq.matches("[0-9A-Za-z_-]{1,30}")) {
            throw ApiException.badRequest("itemSeq", "품목기준코드 형식이 아닙니다.");
        }
        try {
            JsonNode found = pill.item(itemSeq).orElseThrow(() -> ApiException.notFound("약품"));
            return (ObjectNode) found;
        } catch (PillClient.Unavailable e) {
            throw ApiException.retryable(HttpStatus.BAD_GATEWAY, "MM-MED-5020", "약 정보 서버에 연결할 수 없습니다.",
                    "지금은 약 정보를 찾을 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /**
     * 화면 버튼: 약 설명, 주의사항, 복용방법, 보관방법은 항상 넣고(내용이 없으면 안내 문장),
     * 함께 먹으면 안 되는 것과 부작용은 내용이 있을 때만 넣는다.
     */
    public static List<Section> sections(Lookup lookup) {
        Info i = lookup.info();
        String missing = switch (lookup.status()) {
            case "NOT_MATCHED" -> "식약처 자료와 연결되지 않은 약이라 설명이 없습니다. 약사에게 확인해 주세요.";
            case "UNAVAILABLE" -> "지금은 약 설명을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.";
            case "NOT_CONFIGURED" -> "약 설명 기능이 아직 설정되지 않았습니다.";
            default -> NO_INFO;
        };
        List<Section> out = new ArrayList<>();
        out.add(section("DESCRIPTION", "약 설명", i == null ? null : i.efficacy(), missing));
        out.add(section("CAUTION", "주의사항", i == null ? null : join(i.warning(), i.precaution()), missing));
        out.add(section("USE_METHOD", "복용방법", i == null ? null : i.useMethod(), missing));
        out.add(section("STORAGE", "보관방법", i == null ? null : i.depositMethod(), missing));
        if (i != null && i.interaction() != null) {
            out.add(section("INTERACTION", "함께 먹으면 안 되는 것", i.interaction(), missing));
        }
        if (i != null && i.sideEffect() != null) {
            out.add(section("SIDE_EFFECT", "부작용", i.sideEffect(), missing));
        }
        return out;
    }

    private static Section section(String key, String title, String text, String missing) {
        if (text == null || text.isBlank()) {
            return new Section(key, title, null, title + "입니다. " + (missing.equals(NO_INFO) ? "공공데이터에 내용이 없습니다." : missing));
        }
        return new Section(key, title, text, title + "입니다. " + SpeechFormatter.normalize(text));
    }

    private static String join(String a, String b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + " " + b;
    }

    // ── 음성 안내 (명세의 tts-script) ──────────────────────────────

    public TtsScript ttsScript(String itemSeq, List<String> wanted, String verbosity) {
        boolean detailed = "DETAILED".equals(verbosity);
        if (verbosity != null && !detailed && !"SIMPLE".equals(verbosity)) {
            throw ApiException.badRequest("verbosity", "SIMPLE 또는 DETAILED여야 합니다.");
        }
        List<String> sectionsWanted = wanted == null || wanted.isEmpty()
                ? List.of("NAME", "EFFICACY", "USE_METHOD", "WARNING", "SIDE_EFFECT", "DEPOSIT") : wanted;
        ObjectNode item = item(itemSeq);
        Info info = easyInfo(itemSeq).info();
        List<Segment> segments = new ArrayList<>();
        int order = 1;
        for (String s : sectionsWanted) {
            String text = switch (s) {
                case "NAME" -> BagSpeech.name(item.path("itemName").asText(itemSeq)) + ".";
                case "EFFICACY" -> info == null ? null : labeled("효능입니다.", info.efficacy(), detailed);
                case "USE_METHOD" -> info == null ? null : labeled("복용 방법입니다.", info.useMethod(), detailed);
                case "WARNING" -> info == null ? null : labeled("주의할 점입니다.", join(info.warning(), info.precaution()), detailed);
                case "SIDE_EFFECT" -> info == null ? null : labeled("부작용입니다.", info.sideEffect(), detailed);
                case "DEPOSIT" -> info == null ? null : labeled("보관 방법입니다.", info.depositMethod(), detailed);
                default -> throw ApiException.badRequest("sections", "알 수 없는 항목: " + s);
            };
            if (text != null) {
                segments.add(new Segment(s, order++, text, "WARNING".equals(s), 400));
            }
        }
        if (info == null) {
            segments.add(new Segment("NAME", order, NO_INFO, false, 0));
        }
        String full = String.join(" ", segments.stream().map(Segment::text).toList());
        return new TtsScript(itemSeq, detailed ? "DETAILED" : "SIMPLE", estimateSeconds(full), segments, full);
    }

    private static String labeled(String label, String body, boolean detailed) {
        if (body == null || body.isBlank()) {
            return null;
        }
        return label + " " + SpeechFormatter.normalize(detailed ? body : firstSentences(body, SIMPLE_MAX_CHARS));
    }

    /** 문장 단위로 잘라 maxChars 안에서 앞부분만 쓴다. 문장 중간에서 끊지 않는다. */
    static String firstSentences(String text, int maxChars) {
        StringBuilder out = new StringBuilder();
        for (String s : text.split("(?<=[.!?])\\s+")) {
            if (out.length() > 0 && out.length() + s.length() > maxChars) {
                break;
            }
            out.append(out.length() > 0 ? " " : "").append(s);
        }
        return out.toString();
    }

    /** 한국어 TTS 기준 초당 약 6.5자. */
    static int estimateSeconds(String text) {
        return Math.max(1, (int) Math.ceil(text.replace(" ", "").length() / 6.5));
    }
}
