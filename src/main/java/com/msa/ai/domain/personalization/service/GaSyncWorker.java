package com.msa.ai.domain.personalization.service;

/** GA 조회 중 로컬 DB 잠금을 유지하지 않는 작업자 계약입니다. */
public interface GaSyncWorker {
    boolean runNext();
}
