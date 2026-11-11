package com.msa.ai.domain.personalization.entity;

import jakarta.persistence.*;

import java.time.*;
import java.util.UUID;

/** 복제본 전체가 하루 한 번의 성공 분석과 하나의 활성 회차를 공유하도록 직렬화하는 제어 행입니다. */
@Entity
@Table(name = "ga_sync_state", schema = "ai")
public class GaSyncState {
    @Id public String id;
    public UUID activeBatchId;
    public LocalDate completedThrough;
    public UUID leaseToken;
    public Instant leaseUntil;
    public Instant nextAttemptAt;

    protected GaSyncState() {}
}
