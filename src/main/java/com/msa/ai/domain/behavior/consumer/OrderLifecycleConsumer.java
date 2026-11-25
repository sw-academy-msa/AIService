package com.msa.ai.domain.behavior.consumer;

import com.msa.ai.domain.behavior.service.BehaviorService;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 주문 개인정보 토픽 대신 경량 lifecycle만 분석 행동으로 수집합니다. */
@Component
@ConditionalOnProperty(name = "ai.behavior.lifecycle-enabled", havingValue = "true")
public class OrderLifecycleConsumer {
    private final BehaviorService service;

    public OrderLifecycleConsumer(BehaviorService service) {
        this.service = service;
    }

    @KafkaListener(
            id = "ai-behavior",
            topics = "stockdrop.orders.lifecycle.v1",
            groupId = "ai-behavior-v1",
            containerFactory = "behaviorKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, byte[]> record) {
        service.lifecycle(record.key(), record.value());
    }
}
