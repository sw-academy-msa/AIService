package com.msa.ai.domain.analysis.dto.response;

import lombok.Builder;

import java.util.UUID;

/** 작업자 내부 반환 DTO이며 HTTP 응답에 임대 토큰을 노출하지 않습니다. */
@Builder
public record AnalysisWorkResponse(UUID id, UUID leaseToken, AiMessages.Requested request) {}
