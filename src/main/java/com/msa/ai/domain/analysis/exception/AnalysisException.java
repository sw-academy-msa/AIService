package com.msa.ai.domain.analysis.exception;

import com.msa.ai.global.error.BusinessException;

/** 공통 예외 처리기를 재사용하며 공급자 원문 예외는 응답에 포함하지 않습니다. */
public class AnalysisException extends BusinessException {
    private final String diagnostic;

    public AnalysisException(AnalysisErrorCode code) {
        this(code, "analysis.unspecified");
    }

    // 진단에는 코드가 정한 단계와 상태만 넣고 공급자 원문이나 상품 입력은 넣지 않습니다.
    public AnalysisException(AnalysisErrorCode code, String diagnostic) {
        super(code);
        this.diagnostic = diagnostic;
    }

    public String diagnostic() {
        return diagnostic;
    }
}
