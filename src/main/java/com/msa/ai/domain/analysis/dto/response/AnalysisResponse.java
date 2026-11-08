package com.msa.ai.domain.analysis.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/** 판매자 본인 분석 조회 HTTP 응답입니다. 모델 원문이나 이미지 객체 키는 포함하지 않습니다. */
@Builder
public record AnalysisResponse(
        UUID requestId,
        UUID productId,
        long productVersion,
        String fingerprint,
        String status,
        int attempts,
        Instant nextAttemptAt,
        AiMessages.Completed result) {}
