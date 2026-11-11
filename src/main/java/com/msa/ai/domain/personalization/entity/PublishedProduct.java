package com.msa.ai.domain.personalization.entity;

import jakarta.persistence.*;

import java.time.*;
import java.util.UUID;

/** 상품 공개 알림 대상의 분할 처리 커서입니다. 상품 원본이나 재고 모델을 소유하지 않습니다. */
@Entity
@Table(name = "published_products", schema = "ai")
public class PublishedProduct {
    @Id public UUID productId;

    @Column(nullable = false)
    public UUID eventId;

    @Column(nullable = false, columnDefinition = "text")
    public String payload;

    @Column(nullable = false)
    public Instant publishedAt;

    public UUID batchId;
    public LocalDate profileAsOf;
    public UUID lastAnalyticsId;
    public boolean completed;

    protected PublishedProduct() {}
}
