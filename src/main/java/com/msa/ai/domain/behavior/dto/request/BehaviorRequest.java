package com.msa.ai.domain.behavior.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.*;

/** 브라우저가 분석 동의 뒤 이중 전송하는 행동 묶음입니다. */
@lombok.Builder
public record BehaviorRequest(@NotNull @Size(min = 1, max = 20) List<@Valid Event> events) {
    /** 빌더도 입력 원문을 노출하지 않습니다. */
    public static class BehaviorRequestBuilder {
        @Override
        public String toString() {
            return "BehaviorRequestBuilder[REDACTED]";
        }
    }

    @Override
    public String toString() {
        return "BehaviorRequest[REDACTED]";
    }

    /** 클라이언트 키는 재전송 중복 제거에만 사용합니다. */
    @lombok.Builder
    public record Event(
            @NotNull UUID clientEventId,
            @NotBlank String name,
            @NotNull Instant occurredAt,
            UUID productId,
            String searchTerm,
            String listId,
            String promotionId,
            UUID couponIssueId) {
        /** 검색어와 상품 식별자가 빌더 문자열로 유출되지 않게 합니다. */
        public static class EventBuilder {
            @Override
            public String toString() {
                return "EventBuilder[REDACTED]";
            }
        }

        @Override
        public String toString() {
            return "BehaviorEvent[REDACTED]";
        }
    }
}
