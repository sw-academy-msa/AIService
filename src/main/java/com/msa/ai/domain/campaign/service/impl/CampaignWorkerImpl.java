package com.msa.ai.domain.campaign.service.impl;

import com.msa.ai.domain.campaign.client.CampaignEligibilityClient;
import com.msa.ai.domain.campaign.service.*;

import org.springframework.stereotype.Service;

/** HTTP 동안 DB 연결을 점유하지 않으며 다음 회차에서 동의 조회 장애를 다시 확인합니다. */
@Service
public class CampaignWorkerImpl implements CampaignWorker {
    private final CampaignStore store;
    private final CampaignEligibilityClient eligibility;

    public CampaignWorkerImpl(CampaignStore store, CampaignEligibilityClient eligibility) {
        this.store = store;
        this.eligibility = eligibility;
    }

    public void runNext() {
        var token = store.claim();
        if (token == null) return;
        try {
            for (var c : store.candidates(token)) {
                try {
                    if (eligibility.allowed(c.accountId())) store.emit(token, c);
                } catch (com.msa.ai.domain.campaign.client.RemoteUnavailable ex) {
                    /* 동의 원문이나 계정은 로그에 남기지 않습니다. */
                }
            }
        } finally {
            store.complete(token);
        }
    }
}
