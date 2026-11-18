package com.msa.ai.domain.analysis.client;

import com.msa.ai.domain.analysis.dto.response.AiMessages.Image;

/** 검증된 S3 객체를 모델 입력으로 변환합니다. 외부 URL을 따라가지 않습니다. */
public interface ImageContentService {
    String dataUrl(Image image);
}
