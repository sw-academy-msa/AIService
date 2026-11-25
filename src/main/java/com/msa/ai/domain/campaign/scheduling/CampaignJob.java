package com.msa.ai.domain.campaign.scheduling;

import com.msa.ai.domain.campaign.service.CampaignWorker;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 짧은 폴링은 DB lease의 모드별 다음 회차 시각을 따릅니다. */
@Component
public class CampaignJob {
    private final CampaignWorker worker;

    public CampaignJob(CampaignWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelay = 10000, scheduler = "campaignScheduler")
    public void tick() {
        worker.runNext();
    }
}
