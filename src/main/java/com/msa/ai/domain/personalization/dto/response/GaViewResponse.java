package com.msa.ai.domain.personalization.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/** BigQuery의 사용자·상품·날짜별 중복 제거 후 집계된 내부 반환 DTO입니다. HTTP 응답이 아닙니다. */
@Builder
public record GaViewResponse(
        UUID analyticsId, String categoryName, long views, Instant lastViewedAt) {}
