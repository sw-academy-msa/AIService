package com.msa.ai.domain.personalization.service.impl;

import com.msa.ai.domain.personalization.repository.GaViewRepository;
import com.msa.ai.domain.personalization.service.*;

import org.slf4j.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** 외부 집계 실패 시 이전 프로필을 보존하고 영속 재시도 시각만 바꿉니다. */
@Service
public class GaSyncWorkerImpl implements GaSyncWorker {
    private static final Logger log = LoggerFactory.getLogger(GaSyncWorkerImpl.class);
    private final GaSyncService sync;
    private final ObjectProvider<GaViewRepository> source;

    public GaSyncWorkerImpl(GaSyncService sync, ObjectProvider<GaViewRepository> source) {
        this.sync = sync;
        this.source = source;
    }

    public boolean runNext() {
        var repository = source.getIfAvailable();
        if (repository == null) return false;
        var work = sync.claim();
        if (work.isEmpty()) return false;
        try {
            sync.complete(work.get(), repository.fetch(work.get()));
        } catch (Exception ex) {
            log.warn("GA 관심도 갱신 재시도: causeType={}", ex.getClass().getName());
            sync.retry(work.get());
        }
        return true;
    }
}
