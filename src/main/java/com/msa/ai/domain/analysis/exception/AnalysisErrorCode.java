package com.msa.ai.domain.analysis.exception;

import com.msa.ai.global.error.ErrorCode;

import org.springframework.http.HttpStatus;

/** 외부 API 오류 본문·키·상품 입력을 공개하지 않는 분석 도메인 오류입니다. */
public enum AnalysisErrorCode implements ErrorCode {
    INVALID(HttpStatus.BAD_REQUEST, "AI_INVALID_REQUEST", "분석 입력 또는 결과가 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "AI_NOT_FOUND", "분석 요청을 찾을 수 없습니다."),
    NOT_OWNER(HttpStatus.FORBIDDEN, "AI_NOT_OWNER", "본인 분석만 조회할 수 있습니다."),
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "분석 연동을 사용할 수 없습니다.");
    private final HttpStatus status;
    private final String code;
    private final String message;

    AnalysisErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
