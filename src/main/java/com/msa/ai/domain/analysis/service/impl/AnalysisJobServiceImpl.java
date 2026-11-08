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
        if (!stored.requestPayload.equals(payload)) fail();
    }

    public Optional<AnalysisWorkResponse> claim() {
        return jobs.next(clock.instant())
                .map(
                        j -> {
                            j.status = "RUNNING";
                            j.attempts++;
                            j.leaseToken = UUID.randomUUID();
                            // 이미지 다운로드와 모델 응답 제한보다 긴 임대이며 구매 재고의 5분 정책과는 무관합니다.
                            j.leaseUntil = clock.instant().plusSeconds(600);
                            return new AnalysisWorkResponse(
                                    j.id,
                                    j.leaseToken,
                                    json.readValue(j.requestPayload, Requested.class));
                        });
    }

    public void complete(AnalysisWorkResponse work, Completed result) {
        var found = jobs.lock(work.id());
        if (found.isEmpty() || !owns(found.get(), work)) return;
        var j = found.get();
        if (!result.requestId().equals(j.id)
                || !result.productId().equals(j.productId)
                || !result.sellerId().equals(j.sellerId)
                || !result.fingerprint().equals(work.request().fingerprint())) fail();
        j.resultPayload = json.writeValueAsString(result);
        j.status = "COMPLETED";
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
        j.status = "RETRY";
        j.nextAttemptAt =
                clock.instant().plusSeconds(Math.min(3600, 5L << Math.min(10, j.attempts)));
        j.leaseToken = null;
        j.leaseUntil = null;
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
                .result(
                        j.resultPayload == null
                                ? null
                                : json.readValue(j.resultPayload, Completed.class))
                .build();
    }

    private boolean owns(AnalysisJob j, AnalysisWorkResponse work) {
        return j.resultPayload == null
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
                || r.categories().size() > 100) fail();
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
