package com.msa.ai.domain.behavior.scheduling;

import com.msa.ai.domain.behavior.service.BehaviorService;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 수집을 꺼도 이미 저장한 검색어와 행동 원문은 30일 뒤 매일 삭제합니다. */
@Component
public class BehaviorRetentionJob {
    private final BehaviorService service;

    public BehaviorRetentionJob(BehaviorService service) {
        this.service = service;
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void purge() {
        service.purge();
    }
}
