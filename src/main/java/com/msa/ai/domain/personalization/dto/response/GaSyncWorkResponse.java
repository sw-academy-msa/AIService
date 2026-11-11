package com.msa.ai.domain.personalization.dto.response;

import lombok.Builder;

import java.time.LocalDate;
import java.util.UUID;

/** 분석 작업자 내부 임대 DTO이며 브라우저에 공개하지 않습니다. */
@Builder
public record GaSyncWorkResponse(UUID leaseToken, LocalDate from, LocalDate through) {}
