package com.msa.ai.domain.campaign.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.msa.ai.domain.behavior.dto.request.BehaviorRequest;
import com.msa.ai.domain.behavior.service.BehaviorService;
import com.msa.ai.domain.campaign.service.*;
import com.msa.ai.domain.personalization.dto.response.PersonalizationMessages.Published;
import com.msa.ai.domain.personalization.service.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 실제 PostgreSQL에서 수집과 규칙 및 두 복제본의 결정 경쟁을 검증합니다. */
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "ai.behavior.enabled=true",
            "ai.campaign.mode=DEMO",
            "ai.campaign.demo-account-ids=11111111-1111-1111-1111-111111111111,22222222-2222-2222-2222-222222222222"
        })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(CampaignIntegrationTest.TimeConfig.class)
class CampaignIntegrationTest {
    static final org.testcontainers.postgresql.PostgreSQLContainer DB =
            new org.testcontainers.postgresql.PostgreSQLContainer("postgres:17-alpine");

    static {
        DB.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }

    static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-25T03:00:00Z");

        public Instant instant() {
            return now;
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId z) {
            return Clock.fixed(now, z);
        }
    }

    @Autowired BehaviorService behavior;
    @Autowired AnalyticsIdentityService identities;
    @Autowired RecommendationService products;
    @Autowired CampaignStore store;
    @Autowired JdbcTemplate db;
    @Autowired JsonMapper json;
    @Autowired MutableClock clock;
    @Autowired MockMvc mvc;
    @MockitoBean CampaignWorker background;

    @BeforeEach
    void setup() {
        db.execute(
                "truncate"
                    + " ai.behavior_events,ai.behavior_daily_counters,ai.campaign_decisions,ai.campaign_daily_counters,ai.outbox_events,ai.category_interests,ai.analytics_identities,ai.recommendation_candidates,ai.published_products"
                    + " cascade");
        db.execute(
                "update ai.campaign_rules set"
                        + " lease_token=null,lease_until=null,next_run_at='2000-01-01'");
        clock.now = Instant.parse("2026-11-25T03:00:00Z");
        identities.enroll(A);
        identities.enroll(B);
    }

    UUID product(String category, Instant published, Instant starts) {
        UUID id = UUID.randomUUID();
        products.accept(
                new Published(
                        UUID.randomUUID(),
                        1,
                        id,
                        UUID.randomUUID(),
                        "찾던 한정판",
                        category,
                        category,
                        published,
                        starts,
                        clock.instant().plusSeconds(86400)));
        return id;
    }

    void event(UUID a, String name, UUID p, Instant occurred) {
        UUID analytics = identities.get(a).analyticsId();
        db.update(
                "insert into"
                    + " ai.behavior_events(id,account_id,analytics_id,name,product_id,source,occurred_at,received_at)"
                    + " values(?,?,?,?,?,'SERVER',?,?)",
                UUID.randomUUID(),
                a,
                analytics,
                name,
                p,
                java.sql.Timestamp.from(occurred),
                java.sql.Timestamp.from(clock.instant()));
    }

    @Test
    void behaviorDeduplicatesNormalizesRetainsAndCascadesOnWithdrawal() {
        var e =
                new BehaviorRequest.Event(
                        UUID.randomUUID(),
                        "search",
                        clock.instant(),
                        null,
                        "  HELLO   World  ",
                        null,
                        null,
                        null);
        var request = new BehaviorRequest(List.of(e));
        behavior.collect(A, request);
        behavior.collect(A, request);
        assertThat(db.queryForObject("select count(*) from ai.behavior_events", Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("select search_term from ai.behavior_events", String.class))
                .isEqualTo("hello world");
        assertThat(db.queryForObject("select count from ai.behavior_daily_counters", Integer.class))
                .isEqualTo(1);
        identities.withdraw(A);
        assertThat(db.queryForObject("select count(*) from ai.behavior_events", Integer.class))
                .isZero();
        behavior.collect(A, request);
        assertThat(db.queryForObject("select count(*) from ai.behavior_events", Integer.class))
                .isZero();
    }

    @Test
    void dailyCollectionCapAndThirtyDayPurge() {
        db.update(
                "insert into ai.behavior_daily_counters values(?,?,2000)",
                A,
                LocalDate.of(2026, 11, 25));
        behavior.collect(
                A,
                new BehaviorRequest(
                        List.of(
                                new BehaviorRequest.Event(
                                        UUID.randomUUID(),
                                        "view_item",
                                        clock.instant(),
                                        UUID.randomUUID(),
                                        null,
                                        null,
                                        null,
                                        null))));
        assertThat(db.queryForObject("select count(*) from ai.behavior_events", Integer.class))
                .isZero();
        event(B, "search", null, clock.instant().minus(Duration.ofDays(31)));
        behavior.purge();
        assertThat(db.queryForObject("select count(*) from ai.behavior_events", Integer.class))
                .isZero();
    }

    @Test
    void allSixRulesAndDemoAllowlistAreEvaluated() {
        Instant now = clock.instant();
        UUID sale = product("sale", now.minusSeconds(3600), now.minusSeconds(60));
        event(A, "add_to_wishlist", sale, now.minusSeconds(120));
        UUID abandoned = product("abandon", now.minusSeconds(7200), now.minusSeconds(3600));
        event(A, "begin_checkout", abandoned, now.minusSeconds(900));
        event(A, "checkout_abandoned", abandoned, now.minusSeconds(600));
        UUID repeated = product("repeat", now.minusSeconds(7200), now.minusSeconds(3600));
        event(A, "view_item", repeated, now.minusSeconds(120));
        event(A, "view_item", repeated, now.minusSeconds(60));
        UUID old = product("similar", now.minusSeconds(7200), now.minusSeconds(3600));
        event(A, "sold_out_view", old, now.minusSeconds(600));
        product("similar", now.minusSeconds(300), now.minusSeconds(60));
        event(A, "search", null, now.minusSeconds(600));
        db.update("update ai.behavior_events set search_term='찾던' where name='search'");
        product("search", now.minusSeconds(300), now.plusSeconds(3600));
        UUID viewed = product("category", now.minusSeconds(7200), now.minusSeconds(3600));
        event(B, "view_item", viewed, now.minusSeconds(600));
        product("category", now.minusSeconds(300), now.plusSeconds(3600));
        UUID outsider = UUID.randomUUID();
        identities.enroll(outsider);
        event(outsider, "add_to_wishlist", sale, now.minusSeconds(120));
        UUID token = store.claim();
        var seen = new HashSet<String>();
        for (int tick = 0; tick < 6; tick++) {
            var audience = store.candidates(token);
            assertThat(audience).noneMatch(c -> c.accountId().equals(outsider));
            for (var candidate : audience) {
                seen.add(candidate.ruleCode());
                store.emit(token, candidate);
            }
            store.complete(token);
            clock.now = clock.now.plusSeconds(121);
            token = store.claim();
        }
        assertThat(seen)
                .contains(
                        "SALE_OPEN",
                        "CHECKOUT_ABANDON",
                        "SOLD_OUT_SIMILAR",
                        "CATEGORY_NEW",
                        "REPEAT_VIEW",
                        "SEARCH_MATCH");
    }

    @Test
    void demoTickHasOneDecisionAndConcurrentEmitProducesOneOutbox() throws Exception {
        Instant now = clock.instant();
        UUID p = product("sale", now.minusSeconds(3600), now.minusSeconds(60));
        event(A, "add_to_wishlist", p, now.minusSeconds(120));
        UUID p2 = product("second", now.minusSeconds(3600), now.minusSeconds(60));
        event(A, "add_to_wishlist", p2, now.minusSeconds(120));
        UUID token = store.claim();
        assertThat(store.claim()).isNull();
        var targets = store.candidates(token);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 8; i++) tasks.add(() -> store.emit(token, targets.getFirst()));
            for (var result : executor.invokeAll(tasks)) result.get();
        }
        assertThat(store.emit(token, targets.getLast())).isFalse();
        assertThat(db.queryForObject("select count(*) from ai.outbox_events", Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("select count from ai.campaign_daily_counters", Integer.class))
                .isEqualTo(1);
        var payload =
                json.readTree(
                        db.queryForObject("select payload from ai.outbox_events", String.class));
        assertThat(payload.path("type").asText()).isEqualTo("CAMPAIGN_TARGET");
        assertThat(db.queryForObject("select partition_key from ai.outbox_events", String.class))
                .isEqualTo(A.toString());
        store.complete(token);
        clock.now = now.plusSeconds(121);
        UUID second = store.claim();
        assertThat(store.emit(token, targets.getLast())).isFalse();
        assertThat(store.emit(second, store.candidates(second).getFirst())).isTrue();
    }

    @Test
    void exhaustedDemoCounterDoesNotCreateDecisionOrOutbox() {
        UUID p =
                product(
                        "sale",
                        clock.instant().minusSeconds(3600),
                        clock.instant().minusSeconds(60));
        event(A, "add_to_wishlist", p, clock.instant().minusSeconds(120));
        UUID token = store.claim();
        var candidate = store.candidates(token).getFirst();
        db.update(
                "insert into ai.campaign_daily_counters values(?,?,20)",
                A,
                LocalDate.of(2026, 11, 25));
        assertThat(store.emit(token, candidate)).isFalse();
        assertThat(store.candidates(token)).isEmpty();
        assertThat(db.queryForObject("select count(*) from ai.outbox_events", Integer.class))
                .isZero();
    }

    @Test
    void collectionApiRequiresBuyerAndIgnoresUnconsentedAccount() throws Exception {
        String body =
                json.writeValueAsString(
                        new BehaviorRequest(
                                List.of(
                                        new BehaviorRequest.Event(
                                                UUID.randomUUID(),
                                                "search",
                                                clock.instant(),
                                                null,
                                                "term",
                                                null,
                                                null,
                                                null))));
        mvc.perform(post("/api/v1/ai/events").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v1/ai/events")
                                .with(
                                        jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                                                .authorities(
                                                        new SimpleGrantedAuthority("ROLE_BUYER")))
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(db.queryForObject("select count(*) from ai.behavior_events", Integer.class))
                .isZero();
    }

    @Autowired com.msa.ai.domain.campaign.repository.CampaignRepository campaignRows;

    @Autowired
    com.msa.ai.domain.personalization.repository.AnalyticsIdentityRepository identityRows;

    @Autowired com.msa.ai.domain.outbox.repository.OutboxEventRepository outboxRows;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    @Test
    void normalDailyCapAndQuietHoursAreEnforced() {
        var normal =
                new CampaignStoreImpl(
                        campaignRows,
                        identityRows,
                        outboxRows,
                        new com.msa.ai.global.config.campaign.CampaignProperties("NORMAL", ""),
                        json,
                        clock);
        for (int i = 0; i < 3; i++) {
            UUID p =
                    product(
                            "sale" + i,
                            clock.instant().minusSeconds(3600),
                            clock.instant().minusSeconds(60));
            event(A, "add_to_wishlist", p, clock.instant().minusSeconds(120));
        }
        UUID token = store.claim();
        var targets = tx.execute(status -> normal.candidates(token));
        for (var target : targets) tx.executeWithoutResult(status -> normal.emit(token, target));
        assertThat(db.queryForObject("select count(*) from ai.outbox_events", Integer.class))
                .isEqualTo(2);
        assertThat(db.queryForObject("select count from ai.campaign_daily_counters", Integer.class))
                .isEqualTo(2);
        clock.now = Instant.parse("2026-11-25T12:00:00Z");
        UUID nightClaim = tx.execute(status -> normal.claim());
        assertThat(nightClaim).isNull();
    }

    @Test
    void lifecycleDeduplicatesAndConflictingBodyIsRejected() {
        UUID order = UUID.randomUUID(), product = UUID.randomUUID();
        var payload = new LinkedHashMap<String, Object>();
        payload.put(
                "eventId",
                UUID.nameUUIDFromBytes(
                        ("ORDER_LIFECYCLE:" + order + ":HELD")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        payload.put("schemaVersion", 1);
        payload.put("type", "ORDER_LIFECYCLE");
        payload.put("phase", "HELD");
        payload.put("orderId", order);
        payload.put("buyerId", A);
        payload.put("productId", product);
        payload.put("paymentStarted", false);
        payload.put("occurredAt", clock.instant());
        byte[] body = json.writeValueAsBytes(payload);
        behavior.lifecycle(A.toString(), body);
        behavior.lifecycle(A.toString(), body);
        assertThat(db.queryForObject("select name from ai.behavior_events", String.class))
                .isEqualTo("begin_checkout");
        payload.put("quantity", 2);
        assertThatThrownBy(() -> behavior.lifecycle(A.toString(), json.writeValueAsBytes(payload)))
                .isInstanceOf(com.msa.ai.domain.behavior.exception.BehaviorException.class);
        assertThatThrownBy(() -> behavior.lifecycle(B.toString(), body))
                .isInstanceOf(com.msa.ai.domain.behavior.exception.BehaviorException.class);
        identities.withdraw(A);
        assertThat(db.queryForObject("select count(*) from ai.behavior_events", Integer.class))
                .isZero();
    }

    @Test
    void invalidDemoConfigurationFailsClosed() {
        assertThat(new com.msa.ai.global.config.campaign.CampaignProperties("DEMO", "").mode())
                .isEqualTo("OFF");
        assertThat(
                        new com.msa.ai.global.config.campaign.CampaignProperties(
                                        "DEMO", "not-a-uuid")
                                .mode())
                .isEqualTo("OFF");
        assertThat(
                        new com.msa.ai.global.config.campaign.CampaignProperties(
                                        "OFF", A.toString())
                                .enabled())
                .isFalse();
    }

    @Test
    void legacyCategoryCandidateWinningAfterAudienceReadSuppressesCouponDecision() {
        UUID old =
                product(
                        "category",
                        clock.instant().minusSeconds(3600),
                        clock.instant().minusSeconds(1800));
        event(B, "view_item", old, clock.instant().minusSeconds(600));
        UUID fresh =
                product(
                        "category",
                        clock.instant().minusSeconds(300),
                        clock.instant().plusSeconds(3600));
        UUID token = store.claim();
        var target =
                store.candidates(token).stream()
                        .filter(c -> c.accountId().equals(B))
                        .findFirst()
                        .orElseThrow();
        UUID id =
                UUID.nameUUIDFromBytes(
                        ("category-candidate-v1:" + fresh + ":" + B)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        db.update(
                "insert into"
                    + " ai.recommendation_candidates(id,account_id,analytics_id,product_id,created_at)"
                    + " values(?,?,?,?,?)",
                id,
                B,
                identities.get(B).analyticsId(),
                fresh,
                java.sql.Timestamp.from(clock.instant()));
        assertThat(store.emit(token, target)).isFalse();
        assertThat(
                        db.queryForObject(
                                "select count(*) from ai.campaign_daily_counters", Integer.class))
                .isZero();
        assertThat(db.queryForObject("select count(*) from ai.outbox_events", Integer.class))
                .isZero();
    }

    @Test
    void demoModeDoesNotWaitForPersistedNormalDailyCadence() {
        db.update(
                "update ai.campaign_rules set next_run_at=?",
                java.sql.Timestamp.from(clock.instant().plus(Duration.ofDays(1))));
        UUID token = store.claim();
        assertThat(token).isNotNull();
        assertThat(
                        db.queryForObject(
                                "select count(*) from ai.campaign_rules where lease_token=?",
                                Integer.class,
                                token))
                .isEqualTo(7);
        store.complete(token);
        assertThat(store.claim()).isNull();
    }
}
