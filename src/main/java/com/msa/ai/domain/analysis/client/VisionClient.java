package com.msa.ai.domain.analysis.client;

import com.msa.ai.domain.analysis.dto.response.AiMessages.*;

/** 이미지와 허용 기준표를 함께 해석하는 모델 경계입니다. 승인 판단은 포함하지 않습니다. */
public interface VisionClient {
    Completed analyze(Requested request);
}
