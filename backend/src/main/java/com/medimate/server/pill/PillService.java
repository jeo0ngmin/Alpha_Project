package com.medimate.server.pill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medimate.server.common.Api;
import com.medimate.server.common.ApiException;
import com.medimate.server.common.Enums.ColorClass;
import com.medimate.server.common.Enums.DrugShape;
import com.medimate.server.common.Enums.LineType;
import com.medimate.server.common.Enums.PillJobStatus;
import com.medimate.server.common.Ids;
import com.medimate.server.common.JobExecutor;
import com.medimate.server.common.Limits;
import com.medimate.server.common.MedimateProperties;
import com.medimate.server.common.Times;
import com.medimate.server.files.ImageStore;
import com.medimate.server.pill.PillDtos.Candidate;
import com.medimate.server.common.CurrentMedications;
import com.medimate.server.pill.PillDtos.DraftPlan;
import com.medimate.server.pill.PillDtos.IdentificationRequest;
import com.medimate.server.pill.PillDtos.JobAccepted;
import com.medimate.server.pill.PillDtos.JobView;
import com.medimate.server.pill.PillDtos.SelectRequest;
import com.medimate.server.pill.PillDtos.SelectResult;
import com.medimate.server.pill.PillDtos.Source;
import com.medimate.server.pill.PillDtos.TimeSlotEntry;

/**
 * 알약 식별 작업. 작업 상태는 메모리에 둔다: 앱이 몇 초간 폴링하는 짧은 작업이라 서버를 다시 켜면 다시 찍으면 된다.
 * 서버 여러 대로 늘리려면 이 맵을 공유 저장소로 바꿔야 한다.
 */
@Service
public class PillService {

    /**
     * owner: 로그인한 사용자 ID. 로그인 확인이 꺼져 있으면 null이고 누구나 볼 수 있다.
     * mine: 요청 때 앱이 보낸 복용 중인 약의 itemSeq (후보 표시용).
     */
    private record Job(JobView view, Instant expiresAt, String owner, Set<String> mine) {
    }

    private static final Logger log = LoggerFactory.getLogger(PillService.class);
    private static final Pattern ITEM_SEQ = Pattern.compile("[0-9A-Za-z_-]{1,30}");
    private static final int TOP_K = 10;
    private static final Duration JOB_LIFE = Duration.ofHours(24);
    /** 만료 후에도 이 기간은 기억해서 "없음"이 아니라 "만료됨"(410)으로 답한다. */
    private static final Duration FORGET_AFTER = Duration.ofDays(7);

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final PillClient client;
    private final MfdsClient mfds;
    private final ImageStore images;
    private final JobExecutor executor;
    private final Limits limits;
    private final ObjectMapper mapper;
    private final MedimateProperties props;
    private final com.medimate.server.drug.DrugInfoService drugInfo;

    public PillService(PillClient client, MfdsClient mfds, ImageStore images, JobExecutor executor, Limits limits,
                       ObjectMapper mapper, MedimateProperties props,
                       com.medimate.server.drug.DrugInfoService drugInfo) {
        this.drugInfo = drugInfo;
        this.client = client;
        this.mfds = mfds;
        this.images = images;
        this.executor = executor;
        this.limits = limits;
        this.mapper = mapper;
        this.props = props;
    }

    public JobAccepted submit(IdentificationRequest r, String owner, String clientKey) {
        Path front = photo(r.frontFileId() != null ? r.frontFileId() : r.fileId());
        Path back = r.backFileId() == null ? null : photo(r.backFileId());
        String hints = hintsJson(r);
        Set<String> mine = Set.copyOf(CurrentMedications.keys(r.currentMedications()));
        limits.rate("pill:" + clientKey, props.limits().dailyPill(), Duration.ofDays(1));
        String id = Ids.prefixed("pid_");
        jobs.put(id, new Job(JobView.processing(id), Times.now().plus(JOB_LIFE), owner, mine));
        executor.submit(id, () -> run(id, front, back, hints));
        // Claude를 켠 추론 서버는 평균 11초, 길면 30초 가까이 걸린다 (AI Hub 사진 90종 평가)
        return new JobAccepted(id, "PILL_IDENTIFICATION", PillJobStatus.PROCESSING, 12, 2000,
                "알약을 확인하고 있습니다. 십 초쯤 걸립니다. 잠시만 기다려 주세요.");
    }

    private Path photo(String fileId) {
        return images.find(fileId).orElseThrow(() -> ApiException.of(HttpStatus.NOT_FOUND, "MM-FIL-4040",
                "사진을 찾을 수 없습니다.", "사진을 찾을 수 없습니다. 다시 찍어 주세요."));
    }

    private String hintsJson(IdentificationRequest r) {
        if (r.hints() == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(r.hints());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void run(String id, Path front, Path back, String hints) {
        JobView result;
        try {
            byte[] frontBytes = Files.readAllBytes(front);
            byte[] backBytes = back == null ? null : Files.readAllBytes(back);
            result = PillResults.map(id, client.identify(frontBytes, backBytes, hints, TOP_K));
        } catch (PillClient.Unavailable | IOException e) {
            log.warn("식별 실패 {}: {}", id, e.getMessage());
            result = PillResults.failed(id);
        } catch (RuntimeException e) {
            log.error("식별 처리 오류 {}", id, e);
            result = PillResults.failed(id);
        }
        JobView done = result;
        jobs.computeIfPresent(id, (k, j) -> new Job(done, j.expiresAt(), j.owner(), j.mine()));
    }

    public JobView get(String jobId, String owner) {
        Job job = live(jobId, owner);
        JobView v = job.view();
        if (v.candidates() == null || v.candidates().isEmpty() || job.mine().isEmpty()) {
            return v;
        }
        // 복용 중인 약이면 표시한다 (소분해 둔 약을 확인하는 경우)
        Set<String> mine = job.mine();
        List<Candidate> marked = v.candidates().stream().map(c -> mark(c, mine)).toList();
        return new JobView(v.jobId(), v.status(), v.extractedFeatures(), v.topMatch() == null ? null : mark(v.topMatch(), mine),
                marked, v.requiresUserSelection(), v.fallbackAction(), v.ttsScript());
    }

    private static Candidate mark(Candidate c, Set<String> mine) {
        return new Candidate(c.rank(), c.itemSeq(), c.itemName(), c.entpName(), c.className(), c.etcOtcName(),
                c.itemImageUrl(), c.matchScore(), mine.contains(CurrentMedications.key(c.itemSeq(), null)));
    }

    private Job live(String jobId, String owner) {
        Job j = jobs.get(jobId);
        if (j == null) {
            throw ApiException.notFound("식별 작업");
        }
        if (j.owner() != null && !j.owner().equals(owner)) {
            throw ApiException.forbidden("다른 사용자의 식별 결과입니다.");
        }
        if (j.expiresAt().isBefore(Times.now())) {
            throw ApiException.gone("MM-PIL-4100", "결과 보관 기간이 지났습니다.", "결과가 만료되었습니다. 사진을 다시 찍어 주세요.");
        }
        return j;
    }

    /** 사용자가 후보나 수동 검색 결과에서 고른 약을 확정한다. 잘못 골랐으면 다시 고를 수 있다. */
    public SelectResult select(String jobId, SelectRequest r, String owner) {
        Job job = live(jobId, owner);
        if (job.view().status() == PillJobStatus.PROCESSING) {
            throw ApiException.conflict("MM-PIL-4090", "아직 식별 중입니다.", "아직 알약을 확인하고 있습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (!ITEM_SEQ.matcher(r.itemSeq()).matches()) {
            throw ApiException.badRequest("itemSeq", "품목기준코드 형식이 아닙니다.");
        }
        JsonNode found;
        try {
            found = client.item(r.itemSeq()).orElseThrow(() -> ApiException.notFound("약품"));
        } catch (PillClient.Unavailable e) {
            throw unavailable(e);
        }
        ObjectNode medication = (ObjectNode) found;
        addEasyInfo(medication, r.itemSeq());
        jobs.computeIfPresent(jobId, (k, j) -> new Job(j.view().withStatus(PillJobStatus.SELECTED), j.expiresAt(), j.owner(), j.mine()));
        return new SelectResult(medication, draftPlan(r.itemSeq(), medication));
    }

    /** e약은요 설명. 없거나 호출에 실패해도 약 선택은 막지 않는다. */
    private void addEasyInfo(ObjectNode medication, String itemSeq) {
        drugInfo.attachInfo(medication, itemSeq);
    }

    private static DraftPlan draftPlan(String itemSeq, JsonNode medication) {
        boolean capsule = "CAPSULE".equals(medication.path("drugShape").asText())
                || medication.path("dosageForm").asText().contains("캡슐");
        return new DraftPlan(itemSeq, 1.0, capsule ? "CAPSULE" : "TABLET", "DAILY", 1,
                List.of(new TimeSlotEntry("MORNING", "08:00")), "AFTER_MEAL", Times.today().toString(), true);
    }

    public Api.Page<JsonNode> search(DrugShape shape, List<ColorClass> colors, String printText, LineType lineFront,
                                     String itemName, String cursor, Integer size) {
        int limit = Api.pageSize(size);
        Long decoded = Api.decodeCursor(cursor);
        long offset = decoded == null ? 0 : Math.max(decoded, 0);
        JsonNode r;
        try {
            r = client.search(shape, colors, printText, lineFront, itemName, offset, limit);
        } catch (PillClient.Unavailable e) {
            throw unavailable(e);
        }
        boolean hasNext = r.hasNonNull("nextOffset");
        String next = hasNext ? Api.encodeCursor(r.get("nextOffset").asLong()) : null;
        List<JsonNode> items = new ArrayList<>();
        r.path("items").forEach(items::add);
        return new Api.Page<>(items, new Api.PageInfo(limit, hasNext, next, r.path("total").asLong()));
    }

    private static ApiException unavailable(PillClient.Unavailable e) {
        log.warn("추론 서버 호출 실패: {}", e.getMessage());
        return ApiException.retryable(HttpStatus.BAD_GATEWAY, "MM-PIL-5020", "약 정보 서버에 연결할 수 없습니다.",
                "지금은 약 정보를 찾을 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 60_000L)
    public void forgetOldJobs() {
        Instant cutoff = Times.now().minus(FORGET_AFTER);
        jobs.values().removeIf(j -> j.expiresAt().isBefore(cutoff));
    }
}
