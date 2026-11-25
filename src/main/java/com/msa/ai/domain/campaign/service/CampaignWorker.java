package com.msa.ai.domain.campaign.service;

/** 회차 순서대로 동의를 조회하고 대상 결정을 요청합니다. */
public interface CampaignWorker {
    void runNext();
}
