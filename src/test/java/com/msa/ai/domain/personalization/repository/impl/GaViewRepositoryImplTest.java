package com.msa.ai.domain.personalization.repository.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.cloud.bigquery.*;
import com.msa.ai.domain.personalization.dto.response.GaSyncWorkResponse;
import com.msa.ai.domain.personalization.exception.PersonalizationException;
import com.msa.ai.global.config.analytics.GaProperties;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

/** 실제 Google 호출 없이 SDK 요청의 기간 바인딩·중복 제거 SQL·비용 상한·일일 테이블 가드를 검증합니다. */
class GaViewRepositoryImplTest {
    GaProperties properties =
            new GaProperties(
                    true,
                    "synthetic-project",
                    "analytics_123456",
                    "asia-northeast3",
                    "Asia/Seoul",
                    30,
                    1073741824L,
                    100000);
    GaSyncWorkResponse work =
            new GaSyncWorkResponse(
                    UUID.randomUUID(), LocalDate.of(2026, 10, 9), LocalDate.of(2026, 11, 7));

    @Test
    void queryDeduplicatesProductPerDayAndBindsWindowAndLimits() throws Exception {
        var repository = new GaViewRepositoryImpl(mock(BigQuery.class), properties);
        var query = repository.query(work);
        assertThat(query.getQuery())
                .contains(
                        "PARTITION BY user_id, item.item_id, event_date",
                        "event_name = 'view_item'",
                        "privacy_info.analytics_storage = 'Yes'",
                        "synthetic-project.analytics_123456.events_*");
        assertThat(query.getNamedParameters().get("from_date").getValue()).isEqualTo("20261009");
        assertThat(query.getNamedParameters().get("through_date").getValue()).isEqualTo("20261107");
        assertThat(query.getNamedParameters().get("row_limit").getValue()).isEqualTo("100001");
        assertThat(query.getMaximumBytesBilled()).isEqualTo(1073741824L);
    }

    @Test
    void missingDailyExportFailsInsteadOfReplacingProfilesWithEmptyData() throws Exception {
        var bigQuery = mock(BigQuery.class);
        var repository = new GaViewRepositoryImpl(bigQuery, properties);
        assertThatThrownBy(() -> repository.fetch(work))
                .isInstanceOf(PersonalizationException.class);
        verify(bigQuery)
                .getTable(TableId.of("synthetic-project", "analytics_123456", "events_20261107"));
        verifyNoMoreInteractions(bigQuery);
    }

    @Test
    void invalidDatasetCannotBecomeSqlIdentifier() {
        assertThat(
                        new GaProperties(
                                        true,
                                        "synthetic-project",
                                        "analytics_123456;DROP",
                                        "US",
                                        "Asia/Seoul",
                                        30,
                                        1,
                                        1)
                                .isConfigurationValid())
                .isFalse();
    }
}
