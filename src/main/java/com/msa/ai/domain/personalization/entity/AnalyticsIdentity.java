package com.msa.ai.domain.personalization.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** AI 분석 참여용 가명 식별자입니다. Account 식별자는 외래키 없이 논리 참조합니다. */
@Entity
@Table(name = "analytics_identities", schema = "ai")
public class AnalyticsIdentity {
    @Id public UUID accountId;

    @Column(nullable = false, unique = true)
    public UUID analyticsId;

    @Column(nullable = false)
    public Instant consentedAt;

    protected AnalyticsIdentity() {}
}
