package com.msa.ai.domain.personalization.dto.response;

import lombok.Builder;

/** Notification 내부 HTTP 응답입니다. Account의 알림·광고 동의를 대신 확인하지 않습니다. */
@Builder
public record AnalyticsEligibilityResponse(boolean analyticsEnabled) {}
