package com.msa.ai.domain.analysis.entity;

import jakarta.persistence.*;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/** AI 소유 DB의 영속 작업입니다. 복제본은 임대로 작업을 나누며 모델 호출 중 DB 잠금을 유지하지 않습니다. */
@Entity
@Table(
        name = "analysis_jobs",
        schema = "ai",
        // 처리 대상 상태만 찾습니다. 완료 작업도 재시도 시각이 남아 있어 시각만으로는 걸러지지 않습니다.
        indexes = @Index(name = "idx_analysis_job_due", columnList = "status,next_attempt_at"))
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
