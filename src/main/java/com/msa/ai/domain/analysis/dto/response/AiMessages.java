package com.msa.ai.domain.analysis.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.*;

/** Kafka 내부 반환 DTO입니다. 상품·AI DB 공유 없이 불변 입력과 결과를 교환합니다. */
public final class AiMessages {
    private AiMessages() {}

    public static final String REQUEST_TOPIC = "stockdrop.product.analysis-requested.v1";
    public static final String RESULT_TOPIC = "stockdrop.ai.product-analyzed.v1";

    @Builder
    public record Input(
            String name,
            String description,
            String brandOrStore,
            String sourceUrl,
            String category,
            String disposalReason,
            List<Image> images) {}

    @Builder
    public record Image(String objectKey, String contentType, long sizeBytes) {}

    @Builder
    public record Check(String code, String question) {}

    @Builder
    public record Category(String code, String name, List<Check> checks) {}

    @Builder
    public record Requested(
            UUID requestId,
            UUID productId,
            UUID sellerId,
            long productVersion,
            String fingerprint,
            Instant occurredAt,
            Input input,
            List<Category> categories) {}

    @Builder
    public record Question(UUID suggestionId, String checkCode, String question, String reason) {}

    @Builder
    public record Completed(
            UUID requestId,
            UUID productId,
            UUID sellerId,
            String fingerprint,
            String recommendedCategory,
            List<Question> questions) {}
}
