package com.msa.ai.domain.analysis.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.*;

/** Kafka 내부 반환 DTO입니다. 상품·AI DB 공유 없이 불변 입력과 결과를 교환합니다. */
public final class AiMessages {
    private AiMessages() {}

    public static final String REQUEST_TOPIC = "stockdrop.product.analysis-requested.v1";
    public static final String RESULT_TOPIC = "stockdrop.ai.product-analyzed.v1";

    // 판매처 URL은 모델에 전달하지 않아 받지 않습니다. 이전 요청의 sourceUrl 필드는 무시합니다.
    @Builder
    public record Input(
            String name,
            String description,
            String brandOrStore,
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

    // checkCode는 점검 항목에 묶던 이전 결과에만 있고 구매자 질문 결과는 null입니다.
    // suggestedAnswer는 판매자가 확인할 예상 답변 초안이며 근거가 없으면 null입니다.
    @Builder
    public record Question(
            UUID suggestionId,
            String checkCode,
            String question,
            String reason,
            String suggestedAnswer) {}

    /** 추천 결과와 재시도 소진으로 종료된 실패를 구분합니다. */
    public enum Outcome {
        // 허용 카테고리 하나를 추천했습니다.
        RECOMMENDED,
        // 이미지가 허용된 상품 카테고리에 해당하지 않습니다.
        NOT_APPLICABLE,
        // 이미지가 불명확해 카테고리를 판단할 수 없습니다.
        UNCLEAR,
        // 모델이 생성하지 않으며 작업 서비스가 시도 소진 시에만 발행합니다.
        FAILED
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
            List<Question> questions,
            String failureCode) {
        // 이전 성공 결과 생성 코드와 저장 메시지는 실패 코드 없이 그대로 호환합니다.
        public Completed(UUID requestId, UUID productId, UUID sellerId, String fingerprint,
                Outcome outcome, String recommendedCategory, List<Question> questions) {
            this(requestId, productId, sellerId, fingerprint, outcome, recommendedCategory, questions, null);
        }
    }
}
