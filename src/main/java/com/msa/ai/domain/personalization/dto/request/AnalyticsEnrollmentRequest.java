package com.msa.ai.domain.personalization.dto.request;

import jakarta.validation.constraints.*;

/** GA 분석 참여에 대한 명시적 확인입니다. 광고성 알림 수신 동의와는 별개입니다. */
public record AnalyticsEnrollmentRequest(@NotNull @AssertTrue Boolean analyticsConsent) {}
