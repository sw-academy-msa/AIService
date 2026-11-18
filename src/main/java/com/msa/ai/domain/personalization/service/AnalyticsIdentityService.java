package com.msa.ai.domain.personalization.service;

import com.msa.ai.domain.personalization.dto.response.*;

import java.util.UUID;

/** 분석 참여와 본인 가명 매핑의 경계입니다. 알림 수신 설정 원본은 Account에 남습니다. */
public interface AnalyticsIdentityService {
    AnalyticsIdentityResponse enroll(UUID account);

    AnalyticsIdentityResponse get(UUID account);

    void withdraw(UUID account);

    PersonalizationResponse profile(UUID account);

    AnalyticsEligibilityResponse eligible(UUID account, UUID analytics);
}
