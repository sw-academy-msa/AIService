package com.msa.ai.domain.personalization.exception;

import com.msa.ai.global.error.BusinessException;

/** 공통 예외 응답을 재사용하며 BigQuery 원문이나 사용자 행동을 노출하지 않습니다. */
public class PersonalizationException extends BusinessException {
    public PersonalizationException(PersonalizationErrorCode code) {
        super(code);
    }
}
