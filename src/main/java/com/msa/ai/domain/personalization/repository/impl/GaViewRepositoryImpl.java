package com.msa.ai.domain.personalization.repository.impl;

import com.google.cloud.RetryOption;
import com.google.cloud.bigquery.*;
import com.msa.ai.domain.personalization.dto.response.*;
import com.msa.ai.domain.personalization.exception.*;
import com.msa.ai.domain.personalization.repository.GaViewRepository;
import com.msa.ai.global.config.analytics.GaProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** BigQuery 집계 SQL도 저장소에 한정합니다. 식별자는 설정 검증, 기간과 개수는 바인딩으로 처리합니다. */
@Repository
@ConditionalOnProperty(name = "ai.ga.enabled", havingValue = "true")
public class GaViewRepositoryImpl implements GaViewRepository {
    private final BigQuery bigQuery;
    private final GaProperties properties;
    private final String sql;

    public GaViewRepositoryImpl(BigQuery gaBigQuery, GaProperties properties)
            throws java.io.IOException {
        this.bigQuery = gaBigQuery;
        this.properties = properties;
        if (!properties.isConfigurationValid())
            throw new IllegalArgumentException("GA 설정이 올바르지 않습니다.");
        this.sql =
                new ClassPathResource("analytics/category-views.sql")
                        .getContentAsString(StandardCharsets.UTF_8)
                        .replace(
                                "__EVENTS_TABLE__",
                                properties.projectId() + "." + properties.dataset() + ".events_*");
    }

    public List<GaViewResponse> fetch(GaSyncWorkResponse work) {
        Job job = null;
        boolean succeeded = false;
        try {
            // 일일 테이블이 아직 없으면 빈 결과로 기존 프로필을 지우지 않고 재시도합니다.
            String lastTable = "events_" + work.through().format(DateTimeFormatter.BASIC_ISO_DATE);
            if (bigQuery.getTable(
                            TableId.of(properties.projectId(), properties.dataset(), lastTable))
                    == null) unavailable();
            var configuration = query(work);
            var id =
                    JobId.newBuilder()
                            .setProject(properties.projectId())
                            .setLocation(properties.location())
                            .setJob("stockdrop_ga_" + UUID.randomUUID().toString().replace("-", ""))
                            .build();
            job = bigQuery.create(JobInfo.newBuilder(configuration).setJobId(id).build());
            var completed = job.waitFor(RetryOption.totalTimeoutDuration(Duration.ofSeconds(90)));
            if (completed == null) {
                job.cancel();
                unavailable();
            }
            if (completed.getStatus().getError() != null) unavailable();
            var result = completed.getQueryResults(BigQuery.QueryResultsOption.pageSize(1000));
            if (result.getTotalRows() > properties.maximumRows()) unavailable();
            var rows = new ArrayList<GaViewResponse>();
            for (var row : result.iterateAll()) {
                UUID analytics;
                try {
                    analytics = UUID.fromString(row.get("analytics_id").getStringValue());
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                long micros = row.get("last_view_micros").getLongValue();
                rows.add(
                        GaViewResponse.builder()
                                .analyticsId(analytics)
                                .categoryName(row.get("category_name").getStringValue())
                                .views(row.get("views").getLongValue())
                                .lastViewedAt(
                                        Instant.ofEpochSecond(
                                                Math.floorDiv(micros, 1000000),
                                                Math.floorMod(micros, 1000000) * 1000))
                                .build());
            }
            succeeded = true;
            return rows;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PersonalizationException(PersonalizationErrorCode.SOURCE_UNAVAILABLE);
        } catch (Exception ex) {
            throw new PersonalizationException(PersonalizationErrorCode.SOURCE_UNAVAILABLE);
        } finally {
            // 타임아웃 등 실패 시 외부 작업을 방치하지 않도록 가능한 경우 중지를 요청합니다.
            if (job != null && !succeeded) {
                try {
                    job.cancel();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    /** 테스트에서도 조회 기간·비용 상한·행 초과 감지를 실제 요청 구성으로 검증합니다. */
    QueryJobConfiguration query(GaSyncWorkResponse work) {
        return QueryJobConfiguration.newBuilder(sql)
                .setUseLegacySql(false)
                .setMaximumBytesBilled(properties.maximumBytesBilled())
                .setJobTimeoutMs(60000L)
                .addNamedParameter(
                        "from_date",
                        QueryParameterValue.string(
                                work.from().format(DateTimeFormatter.BASIC_ISO_DATE)))
                .addNamedParameter(
                        "through_date",
                        QueryParameterValue.string(
                                work.through().format(DateTimeFormatter.BASIC_ISO_DATE)))
                .addNamedParameter(
                        "row_limit", QueryParameterValue.int64((long) properties.maximumRows() + 1))
                .build();
    }

    private void unavailable() {
        throw new PersonalizationException(PersonalizationErrorCode.SOURCE_UNAVAILABLE);
    }
}
