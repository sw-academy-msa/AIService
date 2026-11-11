package com.msa.ai.domain.analysis.consumer;

import com.msa.ai.domain.analysis.dto.response.AiMessages;
import com.msa.ai.domain.analysis.service.AnalysisJobService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/** 수신 성공은 영속 작업 접수까지입니다. 모델 호출은 Kafka 리스너 밖에서 수행합니다. */
@Component
@ConditionalOnProperty(name = "ai.consumer-enabled", havingValue = "true")
public class AnalysisRequestConsumer {
    private final AnalysisJobService service;
    private final JsonMapper json;

    public AnalysisRequestConsumer(AnalysisJobService service, JsonMapper json) {
        this.service = service;
        this.json = json;
    }

    @KafkaListener(topics = AiMessages.REQUEST_TOPIC, groupId = "ai-product-analysis-v1")
    public void receive(byte[] payload) {
        // Product의 카테고리 기준표 확장과 동일한 1 MiB 기술 상한입니다.
        if (payload.length > 1048576) throw new IllegalArgumentException("AI 입력 크기 제한 초과");
        try {
            service.accept(json.readValue(payload, AiMessages.Requested.class));
        } catch (RuntimeException exception) {
            // 리스너 오류 로그에 공급받은 상품 본문을 남기지 않습니다.
            throw new IllegalArgumentException("AI 분석 요청 저장에 실패했습니다.");
        }
    }
}
