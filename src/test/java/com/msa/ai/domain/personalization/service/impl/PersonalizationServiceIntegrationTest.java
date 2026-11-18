package com.msa.ai.domain.personalization.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.msa.ai.domain.outbox.repository.OutboxEventRepository;
import com.msa.ai.domain.personalization.dto.response.*;
import com.msa.ai.domain.personalization.dto.response.PersonalizationMessages.*;
import com.msa.ai.domain.personalization.exception.*;
import com.msa.ai.domain.personalization.repository.*;
import com.msa.ai.domain.personalization.service.*;
import com.msa.ai.global.config.database.AiDatabaseTestSupport;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 실제 PostgreSQL로 관심도 교체·알림 후보 멱등성·철회·복제본 경합을 검증하며 Google에 접속하지 않습니다. */
@SpringBootTest(
        properties = "ai.personalization.notification-subject=11111111-1111-1111-1111-111111111111")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PersonalizationServiceIntegrationTest.TestTime.class)
class PersonalizationServiceIntegrationTest extends AiDatabaseTestSupport {
    @TestConfiguration
    static class TestTime {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-08T03:00:00Z");

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        public Instant instant() {
            return now;
        }
    }

    @Autowired AnalyticsIdentityService identities;
    @Autowired AnalyticsIdentityRepository identityRows;
    @Autowired CategoryInterestRepository interests;
    @Autowired GaSyncService sync;
    @Autowired GaSyncWorker worker;
    @Autowired GaSyncStateRepository state;
    @Autowired RecommendationService recommendations;
    @Autowired RecommendationCandidateRepository candidates;
    @Autowired PublishedProductRepository publications;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean OutboxEventRepository outbox;
    @Autowired TransactionTemplate tx;
    @Autowired MutableClock clock;
    @Autowired MockMvc mvc;
    @MockitoBean GaViewRepository source;
    UUID account, analytics;

    @BeforeEach
    void setup() {
        outbox.deleteAll();
        candidates.deleteAll();
        publications.deleteAll();
        interests.deleteAll();
        identityRows.deleteAll();
        state.deleteAll();
        clock.now = Instant.parse("2026-11-08T03:00:00Z");
        reset(source);
        account = UUID.randomUUID();
        analytics = identities.enroll(account).analyticsId();
    }

    GaViewResponse row(UUID analytics, String category, long count, int hours) {
        return GaViewResponse.builder()
                .analyticsId(analytics)
                .categoryName(category)
                .views(count)
                .lastViewedAt(clock.instant().minusSeconds(24 * 3600L + hours * 3600L))
                .build();
    }

    void profile(GaViewResponse... rows) {
        var work = sync.claim().orElseThrow();
        sync.complete(work, List.of(rows));
    }

    Published publication(String category) {
        return Published.builder()
                .eventId(UUID.randomUUID())
                .schemaVersion(1)
                .productId(UUID.randomUUID())
                .sellerId(UUID.randomUUID())
                .productName("합성 상품")
                .categoryCode("SYNTHETIC")
                .categoryName(category)
                .publishedAt(clock.instant())
                .startsAt(clock.instant().plusSeconds(3600))
                .endsAt(clock.instant().plusSeconds(14400))
                .build();
    }

    @Test
    void enrollIsIdempotentAndWithdrawalDoesNotReuseOldIdentity() {
        assertThat(identities.enroll(account).analyticsId()).isEqualTo(analytics);
        profile(row(analytics, "의류", 3, 0));
        identities.withdraw(account);
        assertThat(identities.eligible(account, analytics).analyticsEnabled()).isFalse();
        assertThat(interests.count()).isZero();
        assertThat(identities.enroll(account).analyticsId()).isNotEqualTo(analytics);
    }

    @Test
    void highestCountThenMostRecentCategoryWinsAndOnlyOneCategoryRemains() {
        var other = UUID.randomUUID();
        var id = identities.enroll(other).analyticsId();
        profile(
                row(analytics, "의류", 5, 3),
                row(analytics, "문구", 5, 1),
                row(id, "일반 생활잡화", 8, 4),
                row(id, "문구", 2, 0));
        assertThat(identities.profile(account).categoryName()).isEqualTo("문구");
        assertThat(identities.profile(other).categoryName()).isEqualTo("일반 생활잡화");
        assertThat(interests.count()).isEqualTo(2);
        assertThat(sync.claim()).isEmpty();
    }

    @Test
    void failurePreservesLastSuccessfulProfileAndRetriesDurably() {
        profile(row(analytics, "의류", 4, 0));
        var previous = identities.profile(account);
        clock.now = clock.now.plusSeconds(86400);
        when(source.fetch(any()))
                .thenThrow(
                        new PersonalizationException(PersonalizationErrorCode.SOURCE_UNAVAILABLE));
        assertThat(worker.runNext()).isTrue();
        assertThat(identities.profile(account)).isEqualTo(previous);
        assertThat(state.findById("ga4").orElseThrow().nextAttemptAt).isAfter(clock.instant());
    }

    @Test
    void expiredLeaseCannotReplaceProfiles() {
        var work = sync.claim().orElseThrow();
        tx.executeWithoutResult(
                s ->
                        state.findById("ga4").orElseThrow().leaseUntil =
                                clock.instant().minusSeconds(1));
        sync.complete(work, List.of(row(analytics, "의류", 2, 0)));
        assertThat(interests.count()).isZero();
        assertThat(state.findById("ga4").orElseThrow().activeBatchId).isNull();
    }

    @Test
    void duplicatePublicationAndRetryCannotCreateDuplicateCandidate() {
        profile(row(analytics, "의류", 4, 0));
        var e = publication("의류");
        recommendations.accept(e);
        recommendations.accept(e);
        assertThat(recommendations.processNext()).isTrue();
        recommendations.accept(e);
        recommendations.processNext();
        assertThat(candidates.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(outbox.findAll().getFirst().topic)
                .isEqualTo(PersonalizationMessages.CANDIDATE_TOPIC);
    }

    /** 후보나 페이지 커서만 먼저 반영되어 알림이 유실되는 부분 커밋을 막습니다. */
    @Test
    void outboxFailureRollsBackCandidateAndAudienceCursor() {
        profile(row(analytics, "의류", 4, 0));
        var event = publication("의류");
        recommendations.accept(event);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic failure"))
                .when(outbox).save(any(com.msa.ai.domain.outbox.entity.OutboxEvent.class));
        assertThatThrownBy(() -> recommendations.processNext())
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(candidates.count()).isZero();
        assertThat(outbox.count()).isZero();
        var pending = publications.findById(event.productId()).orElseThrow();
        assertThat(pending.completed).isFalse();
        assertThat(pending.lastAnalyticsId).isNull();
    }

    @Test
    void unrelatedCategoryOwnProductAndEndedProductDoNotCreateCandidates() {
        profile(row(analytics, "의류", 3, 0));
        recommendations.accept(publication("문구"));
        recommendations.processNext();
        var own = publication("의류");
        own =
                new Published(
                        own.eventId(),
                        1,
                        own.productId(),
                        account,
                        own.productName(),
                        own.categoryCode(),
                        own.categoryName(),
                        own.publishedAt(),
                        own.startsAt(),
                        own.endsAt());
        recommendations.accept(own);
        recommendations.processNext();
        var ended = publication("의류");
        ended =
                new Published(
                        ended.eventId(),
                        1,
                        ended.productId(),
                        ended.sellerId(),
                        ended.productName(),
                        ended.categoryCode(),
                        ended.categoryName(),
                        clock.instant().minusSeconds(20000),
                        clock.instant().minusSeconds(10000),
                        clock.instant().minusSeconds(1));
        recommendations.accept(ended);
        recommendations.processNext();
        assertThat(candidates.count()).isZero();
    }

    @Test
    void twoWorkersClaimPublicationWithoutDoubleCreatingAudience() throws Exception {
        profile(row(analytics, "의류", 4, 0));
        recommendations.accept(publication("의류"));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return recommendations.processNext();
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return recommendations.processNext();
                            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(candidates.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void unknownAnalyticsIdentityDoesNotBecomeRecipient() {
        profile(row(UUID.randomUUID(), "의류", 100, 0));
        recommendations.accept(publication("의류"));
        recommendations.processNext();
        assertThat(candidates.count()).isZero();
        assertThat(identities.profile(account).categoryName()).isNull();
    }

    @Test
    void audiencePaginationKeepsOriginalBatchWhenDailyProfileChanges() {
        clock.now = Instant.parse("2026-11-08T14:59:00Z");
        var rows = new ArrayList<GaViewResponse>();
        rows.add(row(analytics, "의류", 3, 0));
        for (int i = 0; i < 200; i++)
            rows.add(row(identities.enroll(UUID.randomUUID()).analyticsId(), "의류", 3, 0));
        var work = sync.claim().orElseThrow();
        sync.complete(work, rows);
        recommendations.accept(publication("의류"));
        recommendations.processNext();
        assertThat(candidates.count()).isEqualTo(200);
        // 판매는 계속되는 상태에서 KST 날짜만 넘겨 새 분석 회차를 만듭니다.
        clock.now = clock.now.plusSeconds(120);
        profile(row(analytics, "문구", 20, 0));
        recommendations.processNext();
        assertThat(candidates.count()).isEqualTo(201);
        assertThat(outbox.count()).isEqualTo(201);
    }

    @Test
    void concurrentSyncClaimsYieldOneLease() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return sync.claim();
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return sync.claim();
                            });
            start.countDown();
            int claimed =
                    (first.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0)
                            + (second.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0);
            assertThat(claimed).isEqualTo(1);
        }
    }

    @Test
    void consentApiUsesJwtAccountAndRejectsFalseConsentOrWrongRole() throws Exception {
        var buyer =
                jwt().jwt(j -> j.subject(account.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_BUYER"));
        mvc.perform(
                        post("/api/v1/ai/analytics/identity")
                                .contentType("application/json")
                                .content("{\"analyticsConsent\":true}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v1/ai/analytics/identity")
                                .with(buyer)
                                .contentType("application/json")
                                .content("{\"analyticsConsent\":false}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/v1/ai/analytics/identity")
                                .with(buyer)
                                .contentType("application/json")
                                .content("{\"analyticsConsent\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analyticsId").value(analytics.toString()));
        mvc.perform(
                        get("/api/v1/ai/analytics/profile")
                                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SELLER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void internalEligibilityRequiresDesignatedNotificationSubjectAndScope() throws Exception {
        String path =
                "/internal/v1/personalization/eligibility?accountId="
                        + account
                        + "&analyticsId="
                        + analytics;
        var authority = new SimpleGrantedAuthority("SCOPE_ai.personalization.read");
        var service =
                jwt().jwt(
                                j ->
                                        j.subject("11111111-1111-1111-1111-111111111111")
                                                .claim("scope", "ai.personalization.read")
                                                .claim("principal_type", "service")
                                                .claim("client_id", "notification-service"))
                        .authorities(authority);
        mvc.perform(get(path).with(service))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analyticsEnabled").value(true));
        mvc.perform(
                        get(path)
                                .with(
                                        jwt().jwt(
                                                        j ->
                                                                j.subject(
                                                                                "11111111-1111-1111-1111-111111111111")
                                                                        .claim(
                                                                                "scope",
                                                                                "ai.personalization.read")
                                                                        .claim(
                                                                                "client_id",
                                                                                "notification-service"))
                                                .authorities(authority)))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get(path)
                                .with(
                                        jwt().jwt(
                                                        j ->
                                                                j.subject(
                                                                                "11111111-1111-1111-1111-111111111111")
                                                                        .claim(
                                                                                "scope",
                                                                                "ai.personalization.read")
                                                                        .claim(
                                                                                "principal_type",
                                                                                "service"))
                                                .authorities(authority)))
                .andExpect(status().isForbidden());
        identities.withdraw(account);
        mvc.perform(get(path).with(service))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analyticsEnabled").value(false));
    }
}
