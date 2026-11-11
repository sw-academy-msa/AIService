package com.msa.ai.domain.personalization.service.impl;

import com.msa.ai.domain.personalization.dto.response.*;
import com.msa.ai.domain.personalization.entity.*;
import com.msa.ai.domain.personalization.exception.*;
import com.msa.ai.domain.personalization.repository.*;
import com.msa.ai.domain.personalization.service.GaSyncService;
import com.msa.ai.global.config.analytics.GaProperties;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** 한 회차의 프로필을 모두 저장한 뒤 활성 포인터를 바꿔 부분 갱신 노출을 막습니다. */
@Service
@Transactional
public class GaSyncServiceImpl implements GaSyncService {
    private final GaSyncStateRepository state;
    private final AnalyticsIdentityRepository identities;
    private final CategoryInterestRepository interests;
    private final GaProperties properties;
    private final Clock clock;

    public GaSyncServiceImpl(
            GaSyncStateRepository state,
            AnalyticsIdentityRepository identities,
            CategoryInterestRepository interests,
            GaProperties properties,
            Clock clock) {
        this.state = state;
        this.identities = identities;
        this.interests = interests;
        this.properties = properties;
        this.clock = clock;
    }

    public Optional<GaSyncWorkResponse> claim() {
        state.initialize(clock.instant());
        var s = state.lock().orElseThrow();
        var today = LocalDate.now(clock.withZone(ZoneId.of(properties.zone())));
        var through = today.minusDays(1);
        if ((s.completedThrough != null && !s.completedThrough.isBefore(through))
                || (s.leaseUntil != null && clock.instant().isBefore(s.leaseUntil))
                || (s.nextAttemptAt != null && clock.instant().isBefore(s.nextAttemptAt)))
            return Optional.empty();
        s.leaseToken = UUID.randomUUID();
        s.leaseUntil = clock.instant().plusSeconds(900);
        return Optional.of(
                GaSyncWorkResponse.builder()
                        .leaseToken(s.leaseToken)
                        .from(today.minusDays(properties.lookbackDays()))
                        .through(through)
                        .build());
    }

    public void complete(GaSyncWorkResponse work, List<GaViewResponse> rows) {
        var s = state.lock().orElseThrow();
        if (!owns(s, work)) return;
        if (rows == null || rows.size() > properties.maximumRows()) fail();
        var first = work.from().atStartOfDay(ZoneId.of(properties.zone())).toInstant();
        var end = work.through().plusDays(1).atStartOfDay(ZoneId.of(properties.zone())).toInstant();
        var top = new HashMap<UUID, GaViewResponse>();
        var pairs = new HashSet<String>();
        // 조회 수 우선, 동률이면 최근 조회 우선, 시간까지 같으면 이름 오름차순으로 결정합니다.
        var rank =
                Comparator.comparingLong(GaViewResponse::views)
                        .thenComparing(GaViewResponse::lastViewedAt)
                        .thenComparing(GaViewResponse::categoryName, Comparator.reverseOrder());
        for (var row : rows) {
            if (row == null
                    || row.analyticsId() == null
                    || row.categoryName() == null
                    || row.categoryName().isBlank()
                    || row.categoryName().length() > 200
                    || row.views() < 1
                    || row.lastViewedAt() == null
                    || row.lastViewedAt().isBefore(first)
                    || !row.lastViewedAt().isBefore(end)
                    || !pairs.add(row.analyticsId() + ":" + row.categoryName())) fail();
            top.merge(row.analyticsId(), row, (a, b) -> rank.compare(a, b) >= 0 ? a : b);
        }
        UUID batch = UUID.randomUUID();
        var ids = new ArrayList<>(top.keySet());
        for (int offset = 0; offset < ids.size(); offset += 1000) {
            for (var identity :
                    identities.matching(ids.subList(offset, Math.min(ids.size(), offset + 1000)))) {
                var row = top.get(identity.analyticsId);
                interests.save(
                        CategoryInterest.builder()
                                .batchId(batch)
                                .identity(identity)
                                .categoryName(row.categoryName())
                                .views(row.views())
                                .lastViewedAt(row.lastViewedAt())
                                .windowEnd(work.through())
                                .build());
            }
        }
        interests.flush();
        s.activeBatchId = batch;
        s.completedThrough = work.through();
        s.leaseToken = null;
        s.leaseUntil = null;
        s.nextAttemptAt = clock.instant();
        // 최신 회차와 진행 중인 후보 분할에 필요한 회차만 유지해 행동 요약을 무한히 쌓지 않습니다.
        interests.removeUnusedBatches(batch);
    }

    public void retry(GaSyncWorkResponse work) {
        var found = state.lock();
        if (found.isEmpty() || !owns(found.get(), work)) return;
        var s = found.get();
        s.leaseToken = null;
        s.leaseUntil = null;
        s.nextAttemptAt = clock.instant().plusSeconds(900);
    }

    private boolean owns(GaSyncState s, GaSyncWorkResponse work) {
        return work.leaseToken().equals(s.leaseToken)
                && s.leaseUntil != null
                && clock.instant().isBefore(s.leaseUntil);
    }

    private void fail() {
        throw new PersonalizationException(PersonalizationErrorCode.INVALID);
    }
}
