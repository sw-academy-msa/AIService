package com.msa.ai.domain.campaign.service;

import com.msa.ai.domain.campaign.dto.response.CampaignCandidate;

import java.util.*;

/** 회차 lease와 결정 및 Outbox의 원자적 기록을 담당합니다. */
public interface CampaignStore {
    UUID claim();

    List<CampaignCandidate> candidates(UUID token);

    boolean emit(UUID token, CampaignCandidate candidate);

    void complete(UUID token);
}
