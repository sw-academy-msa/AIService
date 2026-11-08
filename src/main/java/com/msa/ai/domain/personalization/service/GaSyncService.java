package com.msa.ai.domain.personalization.service;

import com.msa.ai.domain.personalization.dto.response.*;

import java.util.*;

/** 외부 분석 조회와 로컬 DB 트랜잭션을 분리하는 영속 동기화 계약입니다. */
public interface GaSyncService {
    Optional<GaSyncWorkResponse> claim();

    void complete(GaSyncWorkResponse work, List<GaViewResponse> rows);

    void retry(GaSyncWorkResponse work);
}
