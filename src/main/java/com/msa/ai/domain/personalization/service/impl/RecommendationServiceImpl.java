package com.msa.ai.domain.personalization.service.impl;

import com.msa.ai.domain.outbox.entity.OutboxEvent;
import com.msa.ai.domain.outbox.repository.OutboxEventRepository;
import com.msa.ai.domain.personalization.dto.response.PersonalizationMessages;
import com.msa.ai.domain.personalization.dto.response.PersonalizationMessages.*;
import com.msa.ai.domain.personalization.entity.RecommendationCandidate;
import com.msa.ai.domain.personalization.exception.*;
import com.msa.ai.domain.personalization.repository.*;
import com.msa.ai.domain.personalization.service.RecommendationService;
import com.msa.ai.global.config.analytics.GaProperties;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;

/** 한 회차의 대상 집합을 고정하고 페이지 커서·후보·Outbox를 같은 트랜잭션으로 진행합니다. */
@Service
@Transactional
public class RecommendationServiceImpl implements RecommendationService {
    private final PublishedProductRepository products;
    private final GaSyncStateRepository state;
    private final CategoryInterestRepository interests;
    private final RecommendationCandidateRepository candidates;
    private final OutboxEventRepository outbox;
    private final GaProperties properties;
    private final JsonMapper json;
    private final Clock clock;

    public RecommendationServiceImpl(
            PublishedProductRepository products,
            GaSyncStateRepository state,
            CategoryInterestRepository interests,
            RecommendationCandidateRepository candidates,
            OutboxEventRepository outbox,
            GaProperties properties,
            JsonMapper json,
            Clock clock) {
        this.products = products;
        this.state = state;
        this.interests = interests;
        this.candidates = candidates;
        this.outbox = outbox;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    public void accept(Published e) {
        if (e == null
                || e.schemaVersion() != 1
                || e.eventId() == null
                || e.productId() == null
                || e.sellerId() == null
                || blank(e.productName())
                || e.productName().length() > 200
                || blank(e.categoryCode())
                || e.categoryCode().length() > 255
                || blank(e.categoryName())
                || e.categoryName().length() > 200
                || e.publishedAt() == null
                || e.startsAt() == null
                || e.endsAt() == null
                || !e.endsAt().isAfter(e.startsAt())) fail();
        String payload = json.writeValueAsString(e);
        products.insertOnce(e.productId(), e.eventId(), payload, e.publishedAt());
        if (!products.findById(e.productId()).orElseThrow().payload.equals(payload)) fail();
    }

    public boolean processNext() {
        var found = products.next();
        if (found.isEmpty()) return false;
        var product = found.get();
        var e = json.readValue(product.payload, Published.class);
        if (!clock.instant().isBefore(e.endsAt())) {
            product.completed = true;
            return true;
        }
        if (product.batchId == null) {
            // 분석 회차 교체·정리와 첫 대상 선택을 직렬화해 아직 필요한 옛 회차가 삭제되지 않게 합니다.
            var active = state.lock();
            if (active.isEmpty()
                    || active.get().activeBatchId == null
                    || active.get().completedThrough == null) return false;
            var today = LocalDate.now(clock.withZone(ZoneId.of(properties.zone())));
            // 3일을 넘긴 과거 통계는 장애 중 새 알림 대상으로 사용하지 않고 새 분석을 기다립니다.
            if (active.get().completedThrough.isBefore(today.minusDays(3))) return false;
            product.batchId = active.get().activeBatchId;
            product.profileAsOf = active.get().completedThrough;
        }
        UUID after = product.lastAnalyticsId == null ? new UUID(0, 0) : product.lastAnalyticsId;
        var page =
                interests.audience(
                        product.batchId, e.categoryName(), after, PageRequest.of(0, 200));
        for (var interest : page) {
            var identity = interest.identity;
            if (!identity.accountId.equals(e.sellerId())) {
                var id =
                        UUID.nameUUIDFromBytes(
                                ("category-candidate-v1:"
                                                + e.productId()
                                                + ":"
                                                + identity.accountId)
                                        .getBytes(StandardCharsets.UTF_8));
                if (!candidates.existsById(id)) {
                    var candidate =
                            Candidate.builder()
                                    .eventId(id)
                                    .schemaVersion(1)
                                    .type("PERSONALIZED_PRODUCT_CANDIDATE")
                                    .accountId(identity.accountId)
                                    .analyticsId(identity.analyticsId)
                                    .productId(e.productId())
                                    .productName(e.productName())
                                    .categoryCode(e.categoryCode())
                                    .categoryName(e.categoryName())
                                    .publishedAt(e.publishedAt())
                                    .startsAt(e.startsAt())
                                    .endsAt(e.endsAt())
                                    .profileAsOf(product.profileAsOf)
                                    .occurredAt(clock.instant())
                                    .build();
                    candidates.save(
                            RecommendationCandidate.builder()
                                    .id(id)
                                    .accountId(identity.accountId)
                                    .analyticsId(identity.analyticsId)
                                    .productId(e.productId())
                                    .createdAt(clock.instant())
                                    .build());
                    outbox.save(
                            OutboxEvent.builder()
                                    .id(id)
                                    .topic(PersonalizationMessages.CANDIDATE_TOPIC)
                                    .productId(e.productId())
                                    .payload(json.writeValueAsString(candidate))
                                    .now(clock.instant())
                                    .build());
                }
            }
            product.lastAnalyticsId = interest.analyticsId;
        }
        if (page.size() < 200) product.completed = true;
        return true;
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private void fail() {
        throw new PersonalizationException(PersonalizationErrorCode.INVALID);
    }
}
