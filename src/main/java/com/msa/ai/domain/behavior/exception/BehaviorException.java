package com.msa.ai.domain.behavior.exception;

import com.msa.ai.global.error.BusinessException;

/** 잘못된 행동 수집 계약을 공통 오류 응답으로 변환합니다. */
public class BehaviorException extends BusinessException {
    public BehaviorException(BehaviorErrorCode code) {
        super(code);
    }
}
