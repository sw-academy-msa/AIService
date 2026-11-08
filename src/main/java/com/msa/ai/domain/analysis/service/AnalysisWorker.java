package com.msa.ai.domain.analysis.service;

/** 모델 호출은 영속 작업 임대를 얻은 뒤 트랜잭션 밖에서 수행합니다. */
public interface AnalysisWorker {
    boolean runNext();
}
