package com.msa.ai.domain.behavior.scheduling;

import com.msa.ai.domain.behavior.service.BehaviorService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 검색어를 포함한 행동 원문을 30일 뒤 매일 삭제합니다. */
@Component
@ConditionalOnProperty(name = "ai.behavior.enabled", havingValue = "true")
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
