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

    /** 추천 여부를 null 대신 명시합니다. 모델 호출 실패는 결과가 아니라 작업 상태(RETRY)로 표현합니다. */
    public enum Outcome {
        // 허용 카테고리 하나를 추천했습니다.
        RECOMMENDED,
        // 이미지가 허용된 상품 카테고리에 해당하지 않습니다.
        NOT_APPLICABLE,
        // 이미지가 불명확해 카테고리를 판단할 수 없습니다.
        UNCLEAR
    }

    // RECOMMENDED일 때만 recommendedCategory가 있고, 나머지는 카테고리 없이 빈 질문 목록입니다.
    @Builder
    public record Completed(
            UUID requestId,
            UUID productId,
            UUID sellerId,
            String fingerprint,
            Outcome outcome,
            String recommendedCategory,
            List<Question> questions) {}
}
