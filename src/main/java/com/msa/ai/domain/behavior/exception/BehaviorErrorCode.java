package com.msa.ai.domain.behavior.exception;

import com.msa.ai.global.error.ErrorCode;

import org.springframework.http.HttpStatus;

/** 행동 계약 오류는 원문을 응답이나 로그에 넣지 않습니다. */
public enum BehaviorErrorCode implements ErrorCode {
    INVALID;

    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }

    public String code() {
        return "BEHAVIOR_INVALID";
    }

    public String message() {
        return "행동 이벤트 형식이 올바르지 않습니다.";
    }
}
