package com.msa.ai.domain.analysis.controller;

import com.msa.ai.domain.analysis.dto.response.AnalysisResponse;
import com.msa.ai.domain.analysis.service.AnalysisJobService;
import com.msa.ai.global.response.*;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 일반 Gateway에서 판매자가 본인 분석만 조회합니다. 상품 저장·승인 API는 제공하지 않습니다. */
@RestController
@RequestMapping("/api/v1/ai/product-analyses")
@PreAuthorize("hasRole('SELLER')")
public class AnalysisController {
    private final AnalysisJobService jobs;

    public AnalysisController(AnalysisJobService jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/{id}")
    public ApiResponse<AnalysisResponse> get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        AnalysisResponse response = jobs.get(UUID.fromString(jwt.getSubject()), id);
        return ApiResponse.onSuccess(response, SuccessCode.OK);
    }
}
