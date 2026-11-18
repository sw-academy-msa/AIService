package com.msa.ai.domain.analysis.exception;

import com.msa.ai.global.error.BusinessException;

/** 공통 예외 처리기를 재사용하며 공급자 원문 예외는 응답에 포함하지 않습니다. */
public class AnalysisException extends BusinessException {
    public AnalysisException(AnalysisErrorCode code) {
        super(code);
    }
}
