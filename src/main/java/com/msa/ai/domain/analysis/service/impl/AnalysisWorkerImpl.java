package com.msa.ai.domain.analysis.service.impl;

import com.msa.ai.domain.analysis.client.VisionClient;
import com.msa.ai.domain.analysis.service.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 실패한 작업은 최초 포함 최대 5회까지 재시도합니다. 응답 유실도 시도를 소비합니다. */
@Service
public class AnalysisWorkerImpl implements AnalysisWorker {
    private static final Logger log = LoggerFactory.getLogger(AnalysisWorkerImpl.class);
    private final AnalysisJobService jobs;
    private final VisionClient vision;

    public AnalysisWorkerImpl(AnalysisJobService jobs, VisionClient vision) {
        this.jobs = jobs;
        this.vision = vision;
    }

    public boolean runNext() {
        var work = jobs.claim();
        if (work.isEmpty()) return false;
        var w = work.get();
        try {
            jobs.complete(w, vision.analyze(w.request()));
        } catch (Exception ex) {
            // 키·본문·외부 오류 메시지는 기록하지 않습니다.
            log.warn("AI 분석 실행 실패: requestId={}, causeType={}", w.id(), ex.getClass().getName());
            jobs.retry(w);
        }
        return true;
    }
}
