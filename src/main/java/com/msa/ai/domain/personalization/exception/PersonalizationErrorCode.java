package com.msa.ai.domain.personalization.exception;

import com.msa.ai.global.error.ErrorCode;

import org.springframework.http.HttpStatus;

/** 분석 참여와 데이터 연동 오류를 외부 원문 없이 구분합니다. */
public enum PersonalizationErrorCode implements ErrorCode {
    INVALID(HttpStatus.BAD_REQUEST, "PERSONALIZATION_INVALID", "분석 입력이 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "PERSONALIZATION_NOT_FOUND", "분석 참여 정보가 없습니다."),
    SOURCE_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "PERSONALIZATION_SOURCE_UNAVAILABLE",
            "분석 원본을 사용할 수 없습니다.");
    private final HttpStatus status;
    private final String code, message;

    PersonalizationErrorCode(HttpStatus status, String code, String message) {
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
