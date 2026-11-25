package com.msa.ai.domain.behavior.service;

import com.msa.ai.domain.behavior.dto.request.BehaviorRequest;

import java.util.UUID;

/** 동의 확인과 하루 수집량 제한을 같은 트랜잭션에서 적용합니다. */
public interface BehaviorService {
    void collect(UUID account, BehaviorRequest request);

    void lifecycle(String key, byte[] payload);

    void purge();
}
