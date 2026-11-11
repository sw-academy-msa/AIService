package com.msa.ai.domain.personalization.entity;

import jakarta.persistence.*;

import lombok.Builder;

import java.time.*;
import java.util.UUID;

/** 한 분석 회차의 최다 조회 카테고리 하나만 저장합니다. 과거 회차는 진행 중인 알림 대상 분할에 필요합니다. */
@Entity
@Table(
        name = "category_interests",
        schema = "ai",
        uniqueConstraints = @UniqueConstraint(columnNames = {"batch_id", "identity_account_id"}),
        indexes =
                @Index(
                        name = "idx_interest_audience",
                        columnList = "batch_id,category_name,analytics_id"))
public class CategoryInterest {
    @Id public UUID id;

    @Column(name = "batch_id", nullable = false)
    public UUID batchId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "identity_account_id", nullable = false)
    public AnalyticsIdentity identity;

    @Column(name = "analytics_id", nullable = false)
    public UUID analyticsId;

    @Column(name = "category_name", nullable = false, length = 200)
    public String categoryName;

    public long views;
    public Instant lastViewedAt;
    public LocalDate windowEnd;

    protected CategoryInterest() {}

    @Builder
    public CategoryInterest(
            UUID batchId,
            AnalyticsIdentity identity,
            String categoryName,
            long views,
            Instant lastViewedAt,
            LocalDate windowEnd) {
        id = UUID.randomUUID();
        this.batchId = batchId;
        this.identity = identity;
        this.analyticsId = identity.analyticsId;
        this.categoryName = categoryName;
        this.views = views;
        this.lastViewedAt = lastViewedAt;
        this.windowEnd = windowEnd;
    }
}
