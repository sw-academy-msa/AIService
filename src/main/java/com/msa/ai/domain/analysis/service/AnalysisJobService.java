package com.msa.ai.domain.analysis.service;

import com.msa.ai.domain.analysis.dto.response.*;

import java.util.*;

/** 영속 요청 수신·임대·결과 저장의 트랜잭션 경계입니다. */
public interface AnalysisJobService {
    void accept(AiMessages.Requested request);

    Optional<AnalysisWorkResponse> claim();

    void complete(AnalysisWorkResponse work, AiMessages.Completed result);

    void retry(AnalysisWorkResponse work);

    AnalysisResponse get(UUID seller, UUID id);
}
