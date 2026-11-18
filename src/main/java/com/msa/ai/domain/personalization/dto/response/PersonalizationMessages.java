package com.msa.ai.domain.personalization.dto.response;

import lombok.Builder;

import java.time.*;
import java.util.UUID;

/** Kafka 내부 DTO입니다. 후보만 전달하고 실제 알림 수신 권한을 확정하지 않습니다. */
public final class PersonalizationMessages {
    private PersonalizationMessages() {}

    public static final String PUBLICATION_TOPIC = "stockdrop.product.published.v1";
    public static final String CANDIDATE_TOPIC = "stockdrop.ai.recommendation-candidates.v1";

    @Builder
    public record Published(
            UUID eventId,
            int schemaVersion,
            UUID productId,
            UUID sellerId,
            String productName,
            String categoryCode,
            String categoryName,
            Instant publishedAt,
            Instant startsAt,
            Instant endsAt) {}

    @Builder
    public record Candidate(
            UUID eventId,
            int schemaVersion,
            String type,
            UUID accountId,
            UUID analyticsId,
            UUID productId,
            String productName,
            String categoryCode,
            String categoryName,
            Instant publishedAt,
            Instant startsAt,
            Instant endsAt,
            LocalDate profileAsOf,
            Instant occurredAt) {}
}
