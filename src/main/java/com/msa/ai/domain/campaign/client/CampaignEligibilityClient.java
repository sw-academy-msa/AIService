package com.msa.ai.domain.campaign.client;

import java.util.UUID;

/** 마케팅 동의를 계산 직전에 Account 원본에서 읽습니다. */
public interface CampaignEligibilityClient {
    boolean allowed(UUID account);
}
