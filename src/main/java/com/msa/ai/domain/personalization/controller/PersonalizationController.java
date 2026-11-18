package com.msa.ai.domain.personalization.controller;

import com.msa.ai.domain.personalization.dto.request.AnalyticsEnrollmentRequest;
import com.msa.ai.domain.personalization.dto.response.*;
import com.msa.ai.domain.personalization.service.AnalyticsIdentityService;
import com.msa.ai.global.response.*;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 일반 Gateway의 BUYER 본인 분석 참여와 프로필 조회입니다. 다른 회원 ID를 요청 본문에서 받지 않습니다. */
@RestController
@RequestMapping(value = "/api/v1/ai/analytics", produces = "application/json")
@PreAuthorize("hasRole('BUYER')")
public class PersonalizationController {
    private final AnalyticsIdentityService service;

    public PersonalizationController(AnalyticsIdentityService service) {
        this.service = service;
    }

    @PostMapping("/identity")
    public ApiResponse<AnalyticsIdentityResponse> enroll(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AnalyticsEnrollmentRequest request) {
        AnalyticsIdentityResponse response = service.enroll(UUID.fromString(jwt.getSubject()));
        return ApiResponse.onSuccess(response, SuccessCode.OK);
    }

    @GetMapping("/identity")
    public ApiResponse<AnalyticsIdentityResponse> identity(@AuthenticationPrincipal Jwt jwt) {
        AnalyticsIdentityResponse response = service.get(UUID.fromString(jwt.getSubject()));
        return ApiResponse.onSuccess(response, SuccessCode.OK);
    }

    @DeleteMapping("/identity")
    public ApiResponse<Void> withdraw(@AuthenticationPrincipal Jwt jwt) {
        service.withdraw(UUID.fromString(jwt.getSubject()));
        return ApiResponse.onSuccess(null, SuccessCode.OK);
    }

    @GetMapping("/profile")
    public ApiResponse<PersonalizationResponse> profile(@AuthenticationPrincipal Jwt jwt) {
        PersonalizationResponse response = service.profile(UUID.fromString(jwt.getSubject()));
        return ApiResponse.onSuccess(response, SuccessCode.OK);
    }
}
