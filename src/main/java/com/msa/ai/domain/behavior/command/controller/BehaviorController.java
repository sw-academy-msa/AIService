package com.msa.ai.domain.behavior.command.controller;

import com.msa.ai.domain.behavior.dto.request.BehaviorRequest;
import com.msa.ai.domain.behavior.service.BehaviorService;
import com.msa.ai.global.response.*;

import io.swagger.v3.oas.annotations.Operation;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 요청 계정은 검증된 JWT 주체만 사용하고 동의 없는 입력도 동일하게 접수합니다. */
@RestController
@RequestMapping(value = "/api/v1/ai/events", produces = "application/json")
@ConditionalOnProperty(name = "ai.behavior.enabled", havingValue = "true")
public class BehaviorController {
    private final BehaviorService service;

    public BehaviorController(BehaviorService service) {
        this.service = service;
    }

    @PostMapping(consumes = "application/json")
    @Operation(
            summary = "분석 동의 기반 행동 이벤트 수집",
            responses =
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "202",
                            description = "접수"))
    public ResponseEntity<ApiResponse<Void>> collect(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BehaviorRequest request) {
        service.collect(UUID.fromString(jwt.getSubject()), request);
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.onSuccess(null, SuccessCode.OK));
    }
}
