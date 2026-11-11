package com.msa.ai.domain.outbox.service;

/** 복제본 간 DB 잠금으로 발행 작업을 분배합니다. */
public interface OutboxPublisher {
    boolean publishNext();
}
