package com.msa.ai.domain.personalization.service;

import com.msa.ai.domain.personalization.dto.response.PersonalizationMessages.Published;

/** 최초 공개 수신과 영속 분할 처리를 분리합니다. 실제 알림 발송은 Notification 책임입니다. */
public interface RecommendationService {
    void accept(Published event);

    boolean processNext();
}
