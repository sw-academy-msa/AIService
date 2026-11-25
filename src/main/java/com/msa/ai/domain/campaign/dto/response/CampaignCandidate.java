package com.msa.ai.domain.campaign.dto.response;

import com.msa.ai.domain.personalization.dto.response.PersonalizationMessages.Published;

import java.util.UUID;

/** 동의 조회를 트랜잭션 밖에서 수행하기 위한 내부 대상 스냅샷입니다. */
@lombok.Builder
public record CampaignCandidate(
        UUID accountId, UUID analyticsId, String ruleCode, Published product) {
    /** 대상 식별자는 빌더 문자열에도 넣지 않습니다. */
    public static class CampaignCandidateBuilder {
        @Override
        public String toString() {
            return "CampaignCandidateBuilder[REDACTED]";
        }
    }

    @Override
    public String toString() {
        return "CampaignCandidate[REDACTED]";
    }
}
