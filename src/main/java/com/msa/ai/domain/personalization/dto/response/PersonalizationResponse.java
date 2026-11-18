package com.msa.ai.domain.personalization.dto.response;

import lombok.Builder;

import java.time.*;

/** 본인 관심도 조회 HTTP 응답입니다. 아직 데이터가 없으면 categoryName은 null입니다. */
@Builder
public record PersonalizationResponse(
        boolean analyticsEnabled,
        String categoryName,
        Long views,
        Instant lastViewedAt,
        LocalDate profileAsOf) {}
