package com.msa.ai.domain.analysis.service.impl;

import com.msa.ai.domain.analysis.dto.response.*;
import com.msa.ai.domain.analysis.dto.response.AiMessages.*;
import com.msa.ai.domain.analysis.entity.AnalysisJob;
import com.msa.ai.domain.analysis.exception.*;
import com.msa.ai.domain.analysis.repository.AnalysisJobRepository;
import com.msa.ai.domain.analysis.service.AnalysisJobService;
import com.msa.ai.domain.outbox.entity.OutboxEvent;
import com.msa.ai.domain.outbox.repository.OutboxEventRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.*;

/** 입력 수신은 멱등하게 저장하고 결과와 Outbox를 같은 AI DB 트랜잭션으로 기록합니다. */
@Service
@Transactional
public class AnalysisJobServiceImpl implements AnalysisJobService {
    // 최초 claim을 포함한 전체 실행 상한입니다. 임대 만료도 외부 호출 유실 가능성이 있어 시도를 소비합니다.
    private static final int MAX_ATTEMPTS = 5;
    private static final String FAILURE_CODE = "AI_ANALYSIS_ATTEMPTS_EXHAUSTED";
    private final AnalysisJobRepository jobs;
    private final OutboxEventRepository outbox;
    private final JsonMapper json;
    private final Clock clock;

    public AnalysisJobServiceImpl(
            AnalysisJobRepository jobs,
            OutboxEventRepository outbox,
            JsonMapper json,
            Clock clock) {
        this.jobs = jobs;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    public void accept(Requested r) {
        validate(r);
        String payload = json.writeValueAsString(r);
        jobs.insertOnce(r.requestId(), r.productId(), r.sellerId(), payload, clock.instant());
        var stored = jobs.findById(r.requestId()).orElseThrow();
        // sourceUrl이 있던 이전 저장 형식도 내용이 같으면 같은 요청으로 봅니다.
        if (!json.readValue(stored.requestPayload, Requested.class).equals(r)) fail();
    }

    public Optional<AnalysisWorkResponse> claim() {
        return jobs.next(clock.instant())
                .flatMap(
                        j -> {
                            // 구버전의 무한 재시도와 다섯 번째 실행의 임대 만료도 추가 모델 호출 없이 종료합니다.
                            if (j.attempts >= MAX_ATTEMPTS) {
                                failPermanently(j);
                                return Optional.empty();
                            }
                            j.status = "RUNNING";
                            j.attempts++;
                            j.leaseToken = UUID.randomUUID();
                            // 이미지 다운로드와 모델 응답 제한보다 긴 임대이며 구매 재고의 5분 정책과는 무관합니다.
                            j.leaseUntil = clock.instant().plusSeconds(600);
                            return Optional.of(new AnalysisWorkResponse(
                                    j.id,
                                    j.leaseToken,
                                    json.readValue(j.requestPayload, Requested.class)));
                        });
    }

    public void complete(AnalysisWorkResponse work, Completed result) {
        var found = jobs.lock(work.id());
        if (found.isEmpty() || !owns(found.get(), work)) return;
        var j = found.get();
        // FAILED는 공급자 응답으로 받아들이지 않으며 시도 상한 처리에서만 생성합니다.
        if (result.outcome() == Outcome.FAILED || result.failureCode() != null) fail();
        if (!result.requestId().equals(j.id)
                || !result.productId().equals(j.productId)
                || !result.sellerId().equals(j.sellerId)
                || !result.fingerprint().equals(work.request().fingerprint())) fail();
        j.resultPayload = json.writeValueAsString(result);
        j.status = "COMPLETED";
        j.nextAttemptAt = null;
        j.leaseToken = null;
        j.leaseUntil = null;
        outbox.save(
                OutboxEvent.builder()
                        .id(j.id)
                        .topic(AiMessages.RESULT_TOPIC)
                        .productId(j.productId)
                        .payload(j.resultPayload)
                        .now(clock.instant())
                        .build());
    }

    public void retry(AnalysisWorkResponse work) {
        var found = jobs.lock(work.id());
        if (found.isEmpty() || !owns(found.get(), work)) return;
        var j = found.get();
        if (j.attempts >= MAX_ATTEMPTS) {
            failPermanently(j);
            return;
        }
        j.status = "RETRY";
        j.nextAttemptAt =
                clock.instant().plusSeconds(Math.min(3600, 5L << Math.min(10, j.attempts)));
        j.leaseToken = null;
        j.leaseUntil = null;
    }

    /** 작업 상태와 실패 이벤트를 같은 트랜잭션에 저장하여 Product가 실패를 놓치지 않게 합니다. */
    private void failPermanently(AnalysisJob j) {
        var request = json.readValue(j.requestPayload, Requested.class);
        j.status = "FAILED";
        j.nextAttemptAt = null;
        j.leaseToken = null;
        j.leaseUntil = null;
        // 실패는 성공 결과 열에 넣지 않습니다. 순차 배포 중 구버전 조회도 새 enum을 읽다 실패하지 않습니다.
        String failurePayload = json.writeValueAsString(new Completed(j.id, j.productId, j.sellerId,
                request.fingerprint(), Outcome.FAILED, null, List.of(), FAILURE_CODE));
        outbox.save(OutboxEvent.builder().id(j.id).topic(AiMessages.RESULT_TOPIC)
                .productId(j.productId).payload(failurePayload).now(clock.instant()).build());
    }

    @Transactional(readOnly = true)
    public AnalysisResponse get(UUID seller, UUID id) {
        var j =
                jobs.findById(id)
                        .orElseThrow(() -> new AnalysisException(AnalysisErrorCode.NOT_FOUND));
        if (!j.sellerId.equals(seller)) throw new AnalysisException(AnalysisErrorCode.NOT_OWNER);
        var r = json.readValue(j.requestPayload, Requested.class);
        return AnalysisResponse.builder()
                .requestId(id)
                .productId(j.productId)
                .productVersion(r.productVersion())
                .fingerprint(r.fingerprint())
                .status(j.status)
                .attempts(j.attempts)
                .nextAttemptAt(j.nextAttemptAt)
                .failureCode("FAILED".equals(j.status) ? FAILURE_CODE : null)
                .result(
                        j.resultPayload == null || "FAILED".equals(j.status)
                                ? null
                                : json.readValue(j.resultPayload, Completed.class))
                .build();
    }

    private boolean owns(AnalysisJob j, AnalysisWorkResponse work) {
        return "RUNNING".equals(j.status) && j.resultPayload == null
                && work.leaseToken().equals(j.leaseToken)
                && j.leaseUntil != null
                && clock.instant().isBefore(j.leaseUntil);
    }

    /** 신뢰된 Kafka에서도 계약을 검증합니다. 임의 URL·버킷·객체 경로는 받지 않습니다. */
    private void validate(Requested r) {
        if (r == null
                || r.requestId() == null
                || r.productId() == null
                || r.sellerId() == null
                || r.fingerprint() == null
                || !r.fingerprint().matches("[a-f0-9]{64}")
                || r.input() == null
                || r.categories() == null
                || r.categories().isEmpty()
                || r.categories().size() > 512) fail();
        var i = r.input();
        if (blank(i.name())
                || i.name().length() > 200
                || blank(i.description())
                || i.description().length() > 20000
                || blank(i.disposalReason())
                || i.disposalReason().length() > 4000
                || i.images() == null
                || i.images().isEmpty()
                || i.images().size() > 10) fail();
        if (i.images().stream().anyMatch(Objects::isNull)
                || i.images().stream().mapToLong(Image::sizeBytes).sum() > 30L * 1024 * 1024)
            fail();
        for (var image : i.images()) {
            if (image == null
                    || image.objectKey() == null
                    || !image.objectKey()
                            .matches("product-images/" + r.productId() + "/[a-f0-9-]{36}")
                    || !Set.of("image/jpeg", "image/png", "image/webp")
                            .contains(image.contentType())
                    || image.sizeBytes() < 1
                    || image.sizeBytes() > 10485760) fail();
        }
        var codes = new HashSet<String>();
        for (var c : r.categories()) {
            if (c == null
                    || blank(c.code())
                    || blank(c.name())
                    || !codes.add(c.code())
                    || c.checks() == null
                    || c.checks().size() > 20) fail();
            var checks = new HashSet<String>();
            for (var check : c.checks())
                if (check == null
                        || blank(check.code())
                        || blank(check.question())
                        || !checks.add(check.code())) fail();
        }
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private void fail() {
        throw new AnalysisException(AnalysisErrorCode.INVALID);
    }
}
