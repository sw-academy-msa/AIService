package com.msa.ai.domain.personalization.service.impl;

import com.msa.ai.domain.personalization.dto.response.*;
import com.msa.ai.domain.personalization.exception.*;
import com.msa.ai.domain.personalization.repository.*;
import com.msa.ai.domain.personalization.service.AnalyticsIdentityService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** 재참여 시 새 가명을 만들고 과거 GA 기록이 새 참여에 자동 연결되지 않게 합니다. */
@Service
@Transactional
public class AnalyticsIdentityServiceImpl implements AnalyticsIdentityService {
    private final AnalyticsIdentityRepository identities;
    private final CategoryInterestRepository interests;
    private final GaSyncStateRepository state;
    private final Clock clock;

    public AnalyticsIdentityServiceImpl(
            AnalyticsIdentityRepository identities,
            CategoryInterestRepository interests,
            GaSyncStateRepository state,
            Clock clock) {
        this.identities = identities;
        this.interests = interests;
        this.state = state;
        this.clock = clock;
    }

    public AnalyticsIdentityResponse enroll(UUID account) {
        identities.insertOnce(account, UUID.randomUUID(), clock.instant());
        return get(account);
    }

    @Transactional(readOnly = true)
    public AnalyticsIdentityResponse get(UUID account) {
        var i =
                identities
                        .findById(account)
                        .orElseThrow(
                                () ->
                                        new PersonalizationException(
                                                PersonalizationErrorCode.NOT_FOUND));
        return AnalyticsIdentityResponse.builder()
                .analyticsId(i.analyticsId)
                .consentedAt(i.consentedAt)
                .build();
    }

    public void withdraw(UUID account) {
        var found = identities.lock(account);
        if (found.isEmpty()) return;
        interests.removeForAccount(account);
        identities.delete(found.get());
        // 이미 Kafka에 전달된 후보도 발송 직전 가명 유효성을 조회하면 차단됩니다. GA 원격 삭제는 별도입니다.
    }

    @Transactional(readOnly = true)
    public PersonalizationResponse profile(UUID account) {
        if (!identities.existsById(account))
            return PersonalizationResponse.builder().analyticsEnabled(false).build();
        var active = state.findById("ga4");
        if (active.isEmpty() || active.get().activeBatchId == null)
            return PersonalizationResponse.builder().analyticsEnabled(true).build();
        var profile = interests.mine(active.get().activeBatchId, account);
        if (profile.isEmpty())
            return PersonalizationResponse.builder()
                    .analyticsEnabled(true)
                    .profileAsOf(active.get().completedThrough)
                    .build();
        var p = profile.get();
        return PersonalizationResponse.builder()
                .analyticsEnabled(true)
                .categoryName(p.categoryName)
                .views(p.views)
                .lastViewedAt(p.lastViewedAt)
                .profileAsOf(p.windowEnd)
                .build();
    }

    @Transactional(readOnly = true)
    public AnalyticsEligibilityResponse eligible(UUID account, UUID analytics) {
        return AnalyticsEligibilityResponse.builder()
                .analyticsEnabled(identities.active(analytics, account) > 0)
                .build();
    }
}
