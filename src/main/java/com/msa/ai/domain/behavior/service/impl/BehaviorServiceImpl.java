package com.msa.ai.domain.behavior.service.impl;

import com.msa.ai.domain.behavior.dto.request.BehaviorRequest;
import com.msa.ai.domain.behavior.exception.*;
import com.msa.ai.domain.behavior.repository.BehaviorRepository;
import com.msa.ai.domain.behavior.service.BehaviorService;
import com.msa.ai.domain.personalization.repository.AnalyticsIdentityRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** 분석 참여 잠금을 철회와 공유해 철회 직후 행동이 다시 저장되지 않게 합니다. */
@Service
@Transactional(timeout = 5)
public class BehaviorServiceImpl implements BehaviorService {
    private static final Set<String> NAMES =
            Set.of(
                    "search",
                    "view_item",
                    "view_item_list",
                    "select_item",
                    "add_to_wishlist",
                    "remove_from_wishlist",
                    "view_promotion",
                    "select_promotion",
                    "coupon_claim",
                    "coupon_use",
                    "sold_out_view");
    private final AnalyticsIdentityRepository identities;
    private final BehaviorRepository events;
    private final JsonMapper json;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    public BehaviorServiceImpl(
            AnalyticsIdentityRepository identities,
            BehaviorRepository events,
            JsonMapper json,
            Clock clock,
            io.micrometer.core.instrument.MeterRegistry metrics) {
        this.identities = identities;
        this.events = events;
        this.json = json;
        this.clock = clock;
        this.metrics = metrics;
    }

    public void collect(UUID account, BehaviorRequest request) {
        if (request == null
                || request.events() == null
                || request.events().isEmpty()
                || request.events().size() > 20
                || json.writeValueAsBytes(request).length > 8192) throw invalid();
        for (var event : request.events())
            if (event == null
                    || event.clientEventId() == null
                    || event.occurredAt() == null
                    || event.name() == null
                    || !NAMES.contains(event.name())) throw invalid();
        var identity = identities.lock(account);
        if (identity.isEmpty()) return;
        for (var event : request.events()) {
            if ("view_item_list".equals(event.name())) continue;
            UUID id =
                    UUID.nameUUIDFromBytes(
                            (account + ":" + event.clientEventId())
                                    .getBytes(StandardCharsets.UTF_8));
            save(
                    id,
                    account,
                    identity.get().analyticsId,
                    event.name(),
                    event.productId(),
                    event.searchTerm(),
                    "WEB",
                    event.occurredAt(),
                    json.writeValueAsString(event));
        }
    }

    public void lifecycle(String key, byte[] payload) {
        tools.jackson.databind.JsonNode e;
        try {
            if (payload == null || payload.length > 16384) throw invalid();
            e = json.readTree(payload);
        } catch (RuntimeException ex) {
            throw invalid();
        }
        try {
            UUID account = UUID.fromString(e.path("buyerId").asText());
            UUID order = UUID.fromString(e.path("orderId").asText());
            UUID id = UUID.fromString(e.path("eventId").asText());
            String phase = e.path("phase").asText();
            if (!account.toString().equals(key)
                    || e.path("schemaVersion").asInt() != 1
                    || !"ORDER_LIFECYCLE".equals(e.path("type").asText())
                    || !Set.of("HELD", "CONFIRMED", "EXPIRED", "FAILED").contains(phase)
                    || !e.path("paymentStarted").isBoolean()
                    || !id.equals(
                            UUID.nameUUIDFromBytes(
                                    ("ORDER_LIFECYCLE:" + order + ":" + phase)
                                            .getBytes(StandardCharsets.UTF_8)))) throw invalid();
            UUID product = UUID.fromString(e.path("productId").asText());
            Instant occurred = Instant.parse(e.path("occurredAt").asText());
            var identity = identities.lock(account);
            if (identity.isEmpty()) return;
            String name =
                    switch (phase) {
                        case "HELD" -> "begin_checkout";
                        case "CONFIRMED" -> "purchase";
                        // 결제창 진입 여부와 관계없이 미확정 종결 주문을 이탈로 기록합니다.
                        case "FAILED", "EXPIRED" -> "checkout_abandoned";
                        default -> null;
                    };
            if (name != null)
                save(
                        id,
                        account,
                        identity.get().analyticsId,
                        name,
                        product,
                        null,
                        "SERVER",
                        occurred,
                        e.toString());
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException ex) {
            throw invalid();
        }
    }

    private void save(
            UUID id,
            UUID account,
            UUID analytics,
            String name,
            UUID product,
            String term,
            String source,
            Instant occurred,
            String original) {
        Instant now = clock.instant();
        String hash = hash(original);
        if (events.duplicate(id, hash)) return;
        if (!events.reserve(account, now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate())) {
            metrics.counter("ai.behavior.dropped", "reason", "daily_cap").increment();
            return;
        }
        if (occurred.isBefore(now.minusSeconds(600)) || occurred.isAfter(now.plusSeconds(600)))
            occurred = now;
        if (term != null) {
            term = term.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            term = term.substring(0, Math.min(50, term.length()));
        }
        events.insert(
                id,
                account,
                analytics,
                name,
                product,
                "search".equals(name) ? term : null,
                source,
                occurred,
                now,
                hash);
    }

    public void purge() {
        events.purge(clock.instant().minus(Duration.ofDays(30)));
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private BehaviorException invalid() {
        return new BehaviorException(BehaviorErrorCode.INVALID);
    }
}
