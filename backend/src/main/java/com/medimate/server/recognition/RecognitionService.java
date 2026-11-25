package com.medimate.server.recognition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medimate.server.common.ApiException;
import com.medimate.server.common.Ids;
import com.medimate.server.common.JobExecutor;
import com.medimate.server.common.Limits;
import com.medimate.server.common.MedimateProperties;
import com.medimate.server.common.Times;
import com.medimate.server.files.ImageStore;
import com.medimate.server.pill.PillClient;
import com.medimate.server.pill.PillDtos.JobAccepted;
import com.medimate.server.common.Enums.PillJobStatus;
import com.medimate.server.recognition.RecognitionDtos.BagItem;
import com.medimate.server.recognition.RecognitionDtos.ConfirmRequest;
import com.medimate.server.recognition.RecognitionDtos.ConfirmResult;
import com.medimate.server.recognition.RecognitionDtos.PlanSummary;
import com.medimate.server.recognition.RecognitionDtos.ItemPatch;
import com.medimate.server.recognition.RecognitionDtos.JobError;
import com.medimate.server.recognition.RecognitionDtos.PlanDraft;
import com.medimate.server.recognition.RecognitionDtos.RecognitionJob;
import com.medimate.server.recognition.RecognitionDtos.RecognitionRequest;
import com.medimate.server.recognition.RecognitionDtos.SkippedItem;
import com.medimate.server.recognition.RecognitionDtos.Source;
import com.medimate.server.recognition.RecognitionDtos.TimeSlotEntry;
import com.medimate.server.speech.SpeechFormatter;
import com.medimate.server.common.CurrentMedications;
import com.medimate.server.drug.DrugInfoService;

/**
 * 약봉투 인식 작업. 사진 → 추론 서버(CLOVA OCR + Claude) → 사용자 확인·수정 → 확정(알림 일정 초안).
 * 확정 결과는 앱이 "알람으로 설정할까요?"라고 물은 뒤 알람과 일정 저장에 쓴다. 서버는 복약 계획을 저장하지 않는다.
 * 작업은 메모리에 둔다. 서버를 다시 켜면 진행 중이던 인식은 다시 찍어야 한다.
 */
@Service
public class RecognitionService {

    private static final Logger log = LoggerFactory.getLogger(RecognitionService.class);
    static final Map<String, LocalTime> DEFAULT_TIMES = Map.of("MORNING", LocalTime.of(8, 0), "LUNCH", LocalTime.of(12, 30),
            "DINNER", LocalTime.of(18, 30), "BEDTIME", LocalTime.of(22, 0));
    private static final List<String> SLOT_ORDER = List.of("MORNING", "LUNCH", "DINNER", "BEDTIME");
    private static final Map<Integer, List<String>> DEFAULT_SLOTS = Map.of(1, List.of("MORNING"),
            2, List.of("MORNING", "DINNER"), 3, List.of("MORNING", "LUNCH", "DINNER"),
            4, List.of("MORNING", "LUNCH", "DINNER", "BEDTIME"));
    private static final Set<String> MEALS = Set.of("BEFORE_MEAL", "AFTER_MEAL", "WITH_MEAL", "EMPTY_STOMACH", "ANYTIME");
    private static final Set<String> RETRYABLE = Set.of("OCR_UNAVAILABLE", "AI_UNAVAILABLE", "RECOGNITION_UNAVAILABLE");
    private static final Pattern ITEM_SEQ = Pattern.compile("[0-9A-Za-z_-]{1,30}");
    private static final Pattern HM = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final Duration JOB_LIFE = Duration.ofHours(24);
    private static final Duration FORGET_AFTER = Duration.ofDays(7);
    static final String ALARM_QUESTION = "알람으로 설정할까요?";

    /** 작업 하나. 사용자의 수정과 확정이 겹치지 않게 이 객체로 잠근다. */
    private static final class Job {
        final String id;
        final String owner;
        final Instant expiresAt;
        final LocalDate hintDate;
        String status = "PROCESSING";
        Double overallConfidence;
        Instant processedAt;
        Long processingMs;
        Source source;
        List<BagItem> items = List.of();
        List<String> generalInstructions = List.of();
        List<String> generalCautions = List.of();
        JobError error;
        ConfirmResult confirmed;

        Job(String id, String owner, Instant expiresAt, LocalDate hintDate) {
            this.id = id;
            this.owner = owner;
            this.expiresAt = expiresAt;
            this.hintDate = hintDate;
        }
    }

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final PillClient client;
    private final ImageStore images;
    private final JobExecutor executor;
    private final Limits limits;
    private final ObjectMapper mapper;
    private final MedimateProperties props;
    private final DrugInfoService drugInfo;

    public RecognitionService(PillClient client, ImageStore images, JobExecutor executor, Limits limits,
                              ObjectMapper mapper, MedimateProperties props,
                              DrugInfoService drugInfo) {
        this.drugInfo = drugInfo;
        this.client = client;
        this.images = images;
        this.executor = executor;
        this.limits = limits;
        this.mapper = mapper;
        this.props = props;
    }

    // ── 인식 요청 ──────────────────────────────────────────────────

    public JobAccepted submit(RecognitionRequest r, String owner, String clientKey) {
        Path photo = images.find(r.fileId()).orElseThrow(() -> ApiException.of(HttpStatus.NOT_FOUND, "MM-FIL-4040",
                "사진을 찾을 수 없습니다.", "사진을 찾을 수 없습니다. 다시 찍어 주세요."));
        limits.rate("recognition:" + clientKey, props.limits().dailyRecognition(), Duration.ofDays(1));
        String id = Ids.prefixed("rcg_");
        Job job = new Job(id, owner, Times.now().plus(JOB_LIFE), r.hintDispensedDate());
        jobs.put(id, job);
        executor.submit(id, () -> run(job, photo, r.ocrRawText()));
        return new JobAccepted(id, "MEDICATION_BAG", PillJobStatus.PROCESSING, 20, 2000,
                "약봉투를 읽고 있습니다. 이십 초쯤 걸립니다. 잠시만 기다려 주세요.");
    }

    private void run(Job job, Path photo, String ocrText) {
        JsonNode result;
        try {
            result = client.recognizeBag(Files.readAllBytes(photo), ocrText);
        } catch (PillClient.Unavailable | IOException e) {
            log.warn("약봉투 인식 실패 {}: {}", job.id, e.getMessage());
            fail(job, new JobError("RECOGNITION_UNAVAILABLE", "약봉투 인식 서버에 연결할 수 없습니다.",
                    "지금은 약봉투를 읽을 수 없습니다. 잠시 후 다시 시도해 주세요.", true));
            return;
        } catch (RuntimeException e) {
            log.error("약봉투 인식 처리 오류 {}", job.id, e);
            fail(job, new JobError("RECOGNITION_FAILED", "약봉투를 처리하지 못했습니다.",
                    "약봉투를 읽지 못했습니다. 다시 찍어 주세요.", false));
            return;
        }
        // 약 설명은 공공데이터를 불러오느라 몇 초 걸릴 수 있어 잠그기 전에 준비한다
        List<BagItem> items = new ArrayList<>();
        if (!"FAILED".equals(result.path("status").asText())) {
            for (JsonNode n : result.path("items")) {
                BagItem it = mapper.convertValue(n, BagItem.class);
                fillInfo(it);
                items.add(it);
            }
        }
        synchronized (job) {
            job.processedAt = Times.now();
            job.processingMs = result.path("processingMs").isNumber() ? result.path("processingMs").asLong() : null;
            if ("FAILED".equals(result.path("status").asText())) {
                JsonNode e = result.path("error");
                String code = e.path("code").asText("RECOGNITION_FAILED");
                job.error = new JobError(code, e.path("message").asText("약봉투를 읽지 못했습니다."),
                        e.path("ttsMessage").asText("약봉투를 읽지 못했습니다. 다시 찍어 주세요."), RETRYABLE.contains(code));
                job.status = "FAILED";
                return;
            }
            JsonNode s = result.path("source");
            String date = text(s, "dispensedDate");
            job.source = new Source(text(s, "pharmacyName"), text(s, "hospitalName"),
                    date != null ? date : job.hintDate == null ? null : job.hintDate.toString(), text(s, "patientNameMasked"));
            job.items = items;
            job.generalInstructions = new ArrayList<>();
            result.path("generalInstructions").forEach(g -> job.generalInstructions.add(g.asText()));
            job.generalCautions = new ArrayList<>();
            result.path("generalCautions").forEach(g -> job.generalCautions.add(g.asText()));
            job.overallConfidence = result.path("overallConfidence").isNumber() ? result.path("overallConfidence").asDouble() : null;
            refresh(job);
        }
    }

    /** 약 상세 정보 화면용 항목을 붙인다. 공공데이터가 실패해도 인식 결과는 그대로 준다. */
    private void fillInfo(BagItem it) {
        try {
            DrugInfoService.Lookup lookup = drugInfo.lookupOrUnmatched(it.itemSeq);
            it.infoStatus = lookup.status();
            it.sections = DrugInfoService.sections(lookup);
        } catch (RuntimeException e) {
            log.warn("약 설명 붙이기 실패: {}", e.getClass().getSimpleName());
            it.infoStatus = "UNAVAILABLE";
            it.sections = DrugInfoService.sections(new DrugInfoService.Lookup("UNAVAILABLE", null, null));
        }
    }

    private void fail(Job job, JobError error) {
        synchronized (job) {
            job.processedAt = Times.now();
            job.error = error;
            job.status = "FAILED";
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    /** 항목이 바뀔 때마다 음성 문장과 상태를 다시 만든다. */
    private void refresh(Job job) {
        int index = 1;
        for (BagItem it : job.items) {
            it.displayText = BagSpeech.display(it);
            it.ttsScript = it.excluded ? null : BagSpeech.item(index++, it);
        }
        boolean review = job.items.stream().anyMatch(i -> !i.excluded && i.needsReview);
        if (!"CONFIRMED".equals(job.status)) {
            job.status = review ? "PARTIAL" : "COMPLETED";
        }
    }

    // ── 조회 ──────────────────────────────────────────────────────

    public RecognitionJob get(String jobId, String owner) {
        Job job = live(jobId, owner);
        synchronized (job) {
            return view(job);
        }
    }

    private RecognitionJob view(Job job) {
        int review = (int) job.items.stream().filter(i -> !i.excluded && i.needsReview).count();
        String summary;
        if ("FAILED".equals(job.status)) {
            summary = job.error == null ? null : job.error.ttsMessage();
        } else if ("PROCESSING".equals(job.status)) {
            summary = null;
        } else {
            summary = BagSpeech.summary(job.items, job.generalCautions);
        }
        return new RecognitionJob(job.id, "MEDICATION_BAG", job.status, job.overallConfidence, Times.kst(job.processedAt),
                job.processingMs, job.source, job.items, review, summary, job.error, job.generalInstructions,
                job.generalCautions);
    }

    private Job live(String jobId, String owner) {
        Job job = jobs.get(jobId);
        if (job == null) {
            throw ApiException.notFound("인식 작업");
        }
        if (job.owner != null && !job.owner.equals(owner)) {
            throw ApiException.forbidden("다른 사용자의 인식 결과입니다.");
        }
        if (job.expiresAt.isBefore(Times.now())) {
            throw ApiException.gone("MM-RCG-4100", "결과 보관 기간이 지났습니다.", "결과가 만료되었습니다. 약봉투를 다시 찍어 주세요.");
        }
        return job;
    }

    private static void requireEditable(Job job) {
        switch (job.status) {
            case "CONFIRMED" -> throw ApiException.conflict("MM-RCG-4090", "이미 확정한 결과입니다.", "이미 확정한 약봉투입니다.");
            case "PROCESSING" -> throw ApiException.conflict("MM-RCG-4092", "아직 읽는 중입니다.",
                    "아직 약봉투를 읽고 있습니다. 잠시 후 다시 시도해 주세요.");
            case "FAILED" -> throw ApiException.conflict("MM-RCG-4093", "인식에 실패한 결과입니다.", "약봉투를 다시 찍어 주세요.");
            default -> {
            }
        }
    }

    // ── 항목 수정 ──────────────────────────────────────────────────

    public BagItem patch(String jobId, String itemId, ItemPatch p, String owner) {
        Job job = live(jobId, owner);
        JsonNode medication = null;
        if (p.itemSeq() != null) {
            if (!ITEM_SEQ.matcher(p.itemSeq()).matches()) {
                throw ApiException.badRequest("itemSeq", "품목기준코드 형식이 아닙니다.");
            }
            try {
                medication = client.item(p.itemSeq()).orElseThrow(() -> ApiException.notFound("약품"));
            } catch (PillClient.Unavailable e) {
                throw ApiException.retryable(HttpStatus.BAD_GATEWAY, "MM-PIL-5020", "약 정보 서버에 연결할 수 없습니다.",
                        "지금은 약 정보를 찾을 수 없습니다. 잠시 후 다시 시도해 주세요.");
            }
        }
        List<String> slots = p.timeSlots() == null ? null : slots(p.timeSlots());
        if (slots != null && p.frequencyPerDay() != null && slots.size() != p.frequencyPerDay()) {
            throw ApiException.badRequest("timeSlots", "복용 시점 수와 하루 횟수가 같아야 합니다.");
        }
        if (p.mealRelation() != null && !MEALS.contains(p.mealRelation())) {
            throw ApiException.badRequest("mealRelation", "BEFORE_MEAL, AFTER_MEAL, WITH_MEAL, EMPTY_STOMACH, ANYTIME 중 하나여야 합니다.");
        }
        synchronized (job) {
            requireEditable(job);
            BagItem it = job.items.stream().filter(i -> i.itemId.equals(itemId)).findFirst()
                    .orElseThrow(() -> ApiException.notFound("인식 항목"));
            if (medication != null) {
                it.itemSeq = medication.path("itemSeq").asText();
                it.itemName = text(medication, "itemName");
                it.entpName = text(medication, "entpName");
                it.dosageForm = text(medication, "dosageForm");
                it.matched = true;
                if (it.dosageUnit == null) {
                    boolean capsule = "CAPSULE".equals(text(medication, "drugShape"))
                            || (it.dosageForm != null && it.dosageForm.contains("캡슐"));
                    it.dosageUnit = capsule ? "CAPSULE" : "TABLET";
                }
                fillInfo(it); // 다른 약을 골랐으면 설명도 그 약 것으로 바꾼다
            }
            if (p.dosageAmount() != null) {
                it.dosageAmount = p.dosageAmount();
            }
            if (slots != null) {
                it.timeSlots = slots;
                it.frequencyPerDay = slots.size();
            } else if (p.frequencyPerDay() != null) {
                it.frequencyPerDay = p.frequencyPerDay();
                if (it.timeSlots == null || it.timeSlots.size() != p.frequencyPerDay()) {
                    List<String> defaults = DEFAULT_SLOTS.get(p.frequencyPerDay());
                    if (defaults == null) {
                        throw ApiException.badRequest("timeSlots", "하루 다섯 번 이상이면 복용 시점도 함께 지정해야 합니다.");
                    }
                    it.timeSlots = defaults;
                }
            }
            if (p.mealRelation() != null) {
                it.mealRelation = p.mealRelation();
            }
            if (p.durationDays() != null) {
                it.durationDays = p.durationDays();
            }
            if (p.excluded() != null) {
                it.excluded = p.excluded();
            }
            // 사용자가 듣고 고쳤으므로 추정이었던 값도 확인된 것으로 본다. 알림에 꼭 필요한 값이 비어 있을 때만 다시 묻는다
            it.slotsInferred = false;
            it.reviewReasons = missing(it);
            it.needsReview = !it.reviewReasons.isEmpty();
            it.totalQuantity = it.dosageAmount != null && it.frequencyPerDay != null && it.durationDays != null
                    ? it.dosageAmount * it.frequencyPerDay * it.durationDays : null;
            refresh(job);
            return it;
        }
    }

    private static List<String> slots(List<String> given) {
        Set<String> unique = new LinkedHashSet<>(given);
        if (unique.isEmpty() || unique.size() != given.size() || !SLOT_ORDER.containsAll(unique)) {
            throw ApiException.badRequest("timeSlots", "MORNING, LUNCH, DINNER, BEDTIME 중에서 겹치지 않게 골라야 합니다.");
        }
        return SLOT_ORDER.stream().filter(unique::contains).toList();
    }

    private static List<String> missing(BagItem it) {
        List<String> out = new ArrayList<>();
        if (it.displayName() == null || it.displayName().isBlank()) {
            out.add("약 이름이 없습니다");
        }
        if (it.dosageAmount == null) {
            out.add("1회량이 없습니다");
        }
        if (it.frequencyPerDay == null || it.timeSlots == null || it.timeSlots.isEmpty()) {
            out.add("복용 시간이 없습니다");
        }
        if (it.durationDays == null) {
            out.add("복용 일수가 없습니다");
        }
        return out;
    }

    // ── 확정 ──────────────────────────────────────────────────────

    public ConfirmResult confirm(String jobId, ConfirmRequest r, String owner) {
        Job job = live(jobId, owner);
        ConfirmRequest req = r == null ? new ConfirmRequest(null, null, null, null, null) : r;
        Map<String, LocalTime> times = times(req.timeSlotOverrides());
        LocalDate today = Times.today();
        LocalDate start = req.startDate() != null ? req.startDate() : today;
        if (start.isBefore(today)) {
            throw ApiException.badRequest("startDate", "시작일은 오늘 이후여야 합니다.");
        }
        synchronized (job) {
            if (job.confirmed != null) {
                return job.confirmed; // 같은 확정을 다시 보내도 결과는 같다
            }
            requireEditable(job);
            Set<String> excluded = new HashSet<>(req.excludeItemIds() == null ? List.of() : req.excludeItemIds());
            Set<String> ids = new HashSet<>();
            job.items.forEach(i -> ids.add(i.itemId));
            for (String id : excluded) {
                if (!ids.contains(id)) {
                    throw ApiException.badRequest("excludeItemIds", "없는 항목입니다: " + id);
                }
            }
            List<BagItem> active = new ArrayList<>(job.items.stream()
                    .filter(i -> !i.excluded && !excluded.contains(i.itemId)).toList());
            if (active.isEmpty()) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "MM-RCG-4223", "확정할 약이 없습니다.",
                        "확정할 약이 없습니다. 약봉투를 다시 찍어 주세요.");
            }
            // 서버는 일정을 보관하지 않는다. 이미 먹는 약인지는 앱이 보낸 목록으로만 판단한다
            Set<String> taking = CurrentMedications.keys(req.currentMedications());
            List<SkippedItem> skipped = new ArrayList<>();
            if (Boolean.TRUE.equals(req.acceptAll())) {
                // 확인 단계 없이 만든다. 알림에 꼭 필요한 값이 없는 약, 이미 복용 중인 약, 봉투 안에서 겹친 약만 뺀다
                Set<String> seen = new HashSet<>();
                for (BagItem it : List.copyOf(active)) {
                    String key = CurrentMedications.key(it.itemSeq, it.displayName());
                    String reason = !missing(it).isEmpty() ? "복용량이나 복용 시간을 읽지 못했습니다"
                            : taking.contains(key) ? "이미 복용 중인 약입니다"
                            : key != null && !seen.add(key) ? "같은 약이 두 번 있습니다" : null;
                    if (reason != null) {
                        active.remove(it);
                        skipped.add(new SkippedItem(it.itemId, it.displayName(), reason));
                    }
                }
                if (active.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MM-RCG-4223", "일정을 만들 약이 없습니다.",
                            "새로 일정을 만들 약이 없습니다.", false,
                            skipped.stream().map(s -> new ApiException.FieldReason(s.itemId(), s.name() + ": " + s.reason())).toList(), null);
                }
            } else {
                List<ApiException.FieldReason> pending = active.stream().filter(i -> i.needsReview)
                        .map(i -> new ApiException.FieldReason(i.itemId, String.join(", ",
                                Objects.requireNonNullElse(i.reviewReasons, List.of("확인이 필요합니다")))))
                        .toList();
                if (!pending.isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "MM-RCG-4091", "확인이 필요한 약이 있습니다.",
                            "확인이 필요한 약이 " + SpeechFormatter.nativeCount(pending.size())
                                    + " 가지 있습니다. 먼저 확인하거나 목록에서 빼 주세요.", false, pending, null);
                }
                List<ApiException.FieldReason> duplicates = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (BagItem it : active) {
                    String key = CurrentMedications.key(it.itemSeq, it.displayName());
                    if (taking.contains(key)) {
                        duplicates.add(new ApiException.FieldReason(it.itemId, it.displayName() + ": 이미 복용 중인 약입니다"));
                    } else if (key != null && !seen.add(key)) {
                        duplicates.add(new ApiException.FieldReason(it.itemId, it.displayName() + ": 같은 약이 두 번 있습니다"));
                    }
                }
                if (!duplicates.isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "MM-RCG-4094", "이미 복용 중인 약이 있습니다.",
                            "이미 복용 중인 약이 있습니다. 기존 일정을 끝내거나 이 약을 목록에서 빼 주세요.", false, duplicates, null);
                }
            }
            ConfirmResult draft = build(job, active, start, times, today);
            String script = draft.ttsScript();
            if (!skipped.isEmpty()) {
                // 뺀 약을 알림 질문 앞에 짧게 알린다
                String names = String.join(", ", skipped.stream().map(s -> BagSpeech.name(s.name())).toList());
                script = script.replace(" " + ALARM_QUESTION, " " + names + "은 일정에서 뺐습니다. " + ALARM_QUESTION);
            }
            ConfirmResult result = new ConfirmResult(draft.jobId(), draft.status(), draft.plans(), draft.summaries(),
                    draft.totalDoseCount(), draft.firstDoseAt(), draft.interactionWarnings(), draft.alarmQuestion(),
                    script, skipped);
            job.confirmed = result;
            job.status = "CONFIRMED";
            return result;
        }
    }

    private static Map<String, LocalTime> times(Map<String, String> overrides) {
        Map<String, LocalTime> out = new HashMap<>(DEFAULT_TIMES);
        if (overrides == null) {
            return out;
        }
        overrides.forEach((slot, time) -> {
            if (!DEFAULT_TIMES.containsKey(slot)) {
                throw ApiException.badRequest("timeSlotOverrides", "MORNING, LUNCH, DINNER, BEDTIME만 바꿀 수 있습니다.");
            }
            if (time == null || !HM.matcher(time).matches()) {
                throw ApiException.badRequest("timeSlotOverrides", "시각은 HH:mm 형식이어야 합니다.");
            }
            try {
                out.put(slot, LocalTime.parse(time));
            } catch (DateTimeParseException e) {
                throw ApiException.badRequest("timeSlotOverrides", "시각은 HH:mm 형식이어야 합니다.");
            }
        });
        return out;
    }

    private ConfirmResult build(Job job, List<BagItem> active, LocalDate start, Map<String, LocalTime> times, LocalDate today) {
        Instant now = Times.now();
        List<PlanDraft> plans = new ArrayList<>();
        List<PlanSummary> summaries = new ArrayList<>();
        int total = 0;
        Instant first = null;
        for (BagItem it : active) {
            LocalDate end = start.plusDays(it.durationDays - 1L);
            List<TimeSlotEntry> entries = it.timeSlots.stream()
                    .map(s -> new TimeSlotEntry(s, times.get(s).toString())).toList();
            int count = 0;
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                for (String s : it.timeSlots) {
                    Instant at = d.atTime(times.get(s)).atZone(Times.KST).toInstant();
                    if (at.isBefore(now)) {
                        continue; // 이미 지난 시각에는 알림을 만들지 않는다
                    }
                    count++;
                    first = first == null || at.isBefore(first) ? at : first;
                }
            }
            total += count;
            boolean known = it.itemSeq != null;
            plans.add(new PlanDraft(known ? it.itemSeq : null, known ? null : truncate(it.displayName(), 50), it.dosageAmount,
                    it.dosageUnit == null ? "TABLET" : it.dosageUnit, "DAILY", it.frequencyPerDay, entries,
                    it.mealRelation == null ? "ANYTIME" : it.mealRelation,
                    it.mealOffsetMinutes == null ? null : Math.min(120, Math.max(0, it.mealOffsetMinutes)), start, end,
                    it.durationDays, it.totalQuantity, true, memo(job, it)));
            summaries.add(new PlanSummary(it.itemId, it.itemSeq, it.displayName(), start, end, count));
        }
        String script = "약 " + SpeechFormatter.nativeCount(active.size()) + " 가지를 확정했습니다.";
        if (first != null) {
            var at = Times.kst(first);
            script += " 첫 복용은 " + BagSpeech.day(at.toLocalDate(), today) + " " + BagSpeech.time(at.toLocalTime()) + "입니다.";
        } else {
            script += " 남은 복용 시간이 없습니다.";
        }
        return new ConfirmResult(job.id, "CONFIRMED", plans, summaries, total, Times.kst(first), List.of(), ALARM_QUESTION,
                script + " " + ALARM_QUESTION, List.of());
    }

    /** 알림을 볼 때 함께 보여 줄 메모: 주의사항이 있으면 먼저, 그다음 조제 약국. 200자까지. */
    private static String memo(Job job, BagItem it) {
        List<String> parts = new ArrayList<>();
        List<String> cautions = new ArrayList<>(it.cautions == null ? List.of() : it.cautions);
        cautions.addAll(job.generalCautions);
        if (!cautions.isEmpty()) {
            parts.add("주의: " + String.join(" / ", new LinkedHashSet<>(cautions)));
        }
        if (job.source != null && job.source.pharmacyName() != null) {
            parts.add(job.source.pharmacyName() + " 약봉투"
                    + (job.source.dispensedDate() == null ? "" : " (" + job.source.dispensedDate() + " 조제)"));
        }
        return parts.isEmpty() ? null : truncate(String.join(" · ", parts), 200);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 60_000L)
    public void forgetOldJobs() {
        Instant cutoff = Times.now().minus(FORGET_AFTER);
        jobs.values().removeIf(j -> j.expiresAt.isBefore(cutoff));
    }
}
