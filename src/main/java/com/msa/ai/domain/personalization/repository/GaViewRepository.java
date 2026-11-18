package com.msa.ai.domain.personalization.repository;

import com.msa.ai.domain.personalization.dto.response.*;

import java.util.List;

/** 외부 분석 저장소의 집계 조회 계약입니다. 로컬 업무 저장은 JPA 저장소가 담당합니다. */
public interface GaViewRepository {
    List<GaViewResponse> fetch(GaSyncWorkResponse work);
}
