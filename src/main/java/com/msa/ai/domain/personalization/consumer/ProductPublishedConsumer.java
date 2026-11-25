package com.msa.ai.domain.personalization.consumer;

import com.msa.ai.domain.personalization.dto.response.PersonalizationMessages;
import com.msa.ai.domain.personalization.service.RecommendationService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * 공개 이벤트를 먼저 영속화하고 대상자 계산은 별도 작업으로 수행합니다. GA 추천과 맞춤 쿠폰 캠페인이 같은 공개 상품을 읽으므로 둘 중
 * 하나라도 켜져 있으면 수신합니다.
 */
@Component
@ConditionalOnExpression(
        "${ai.personalization.enabled:false}"
                + " or !'OFF'.equalsIgnoreCase('${ai.campaign.mode:OFF}'.trim())")
public class ProductPublishedConsumer {
    private final RecommendationService service;
    private final JsonMapper json;

    public ProductPublishedConsumer(RecommendationService service, JsonMapper json) {
        this.service = service;
        this.json = json;
    }

    @KafkaListener(
            topics = PersonalizationMessages.PUBLICATION_TOPIC,
            groupId = "ai-category-recommendations-v1")
    public void receive(byte[] bytes) {
        if (bytes.length > 16384) throw new IllegalArgumentException("상품 공개 이벤트 크기 초과");
        try {
            service.accept(json.readValue(bytes, PersonalizationMessages.Published.class));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("상품 공개 이벤트 저장 실패");
        }
    }
}
