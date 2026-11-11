package com.msa.ai.domain.analysis.entity;

import jakarta.persistence.*;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/** AI 소유 DB의 영속 작업입니다. 복제본은 임대로 작업을 나누며 모델 호출 중 DB 잠금을 유지하지 않습니다. */
@Entity
@Table(name = "analysis_jobs", schema = "ai")
public class AnalysisJob {
    @Id public UUID id;

    @Column(nullable = false)
    public UUID productId;

    @Column(nullable = false)
    public UUID sellerId;

    @Column(nullable = false, columnDefinition = "text")
    public String requestPayload;

    @Column(columnDefinition = "text")
    public String resultPayload;

    @Column(nullable = false)
    public String status;

    public int attempts;
    public UUID leaseToken;
    public Instant leaseUntil;
    public Instant nextAttemptAt;
    public Instant createdAt;

    protected AnalysisJob() {}

    @Builder
    public AnalysisJob(UUID id, UUID productId, UUID sellerId, String payload, Instant now) {
        this.id = id;
        this.productId = productId;
        this.sellerId = sellerId;
        requestPayload = payload;
        status = "PENDING";
        createdAt = now;
        nextAttemptAt = now;
    }
}
