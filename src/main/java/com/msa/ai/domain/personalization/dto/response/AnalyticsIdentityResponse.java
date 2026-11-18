package com.msa.ai.domain.personalization.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/** 프런트가 GA user_id로 사용할 가명 식별자입니다. Account ID나 이메일을 GA에 보내지 않습니다. */
@Builder
public record AnalyticsIdentityResponse(UUID analyticsId, Instant consentedAt) {}
