package com.msa.ai.domain.personalization.entity;

import jakarta.persistence.*;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/** 동일 사용자·상품의 후보 중복을 DB에서도 방지합니다. 후보는 수신 동의나 실제 발송 성공이 아닙니다. */
@Entity
@Table(
        name = "recommendation_candidates",
        schema = "ai",
        uniqueConstraints = @UniqueConstraint(columnNames = {"productId", "accountId"}))
public class RecommendationCandidate {
    @Id public UUID id;

    @Column(nullable = false)
    public UUID productId;

    @Column(nullable = false)
    public UUID accountId;

    @Column(nullable = false)
    public UUID analyticsId;

    public Instant createdAt;

    protected RecommendationCandidate() {}

    @Builder
    public RecommendationCandidate(
            UUID id, UUID productId, UUID accountId, UUID analyticsId, Instant createdAt) {
        this.id = id;
        this.productId = productId;
        this.accountId = accountId;
        this.analyticsId = analyticsId;
        this.createdAt = createdAt;
    }
}
