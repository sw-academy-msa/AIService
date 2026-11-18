package com.msa.ai.domain.personalization.controller;

import com.msa.ai.domain.personalization.dto.response.AnalyticsEligibilityResponse;
import com.msa.ai.domain.personalization.service.AnalyticsIdentityService;
import com.msa.ai.global.response.*;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Notification의 발송 직전 내부 검증입니다. 외부 Gateway에 라우팅하지 않습니다. */
@RestController
@RequestMapping(value = "/internal/v1/personalization/eligibility", produces = "application/json")
public class AnalyticsEligibilityController {
    private final AnalyticsIdentityService service;

    public AnalyticsEligibilityController(AnalyticsIdentityService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@notificationServiceAccess.allowed(#jwt)")
    public ApiResponse<AnalyticsEligibilityResponse> get(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam UUID accountId,
            @RequestParam UUID analyticsId) {
        AnalyticsEligibilityResponse response = service.eligible(accountId, analyticsId);
        return ApiResponse.onSuccess(response, SuccessCode.OK);
    }
}
