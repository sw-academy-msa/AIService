package com.msa.ai.global.config.kafka;

import org.springframework.context.annotation.*;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** DB 수신 실패나 잘못된 계약을 자동 유실시키지 않습니다. 독성 메시지는 운영 격리가 필요합니다. */
@Configuration
public class KafkaConfig {
    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        var h = new DefaultErrorHandler(new FixedBackOff(5000, FixedBackOff.UNLIMITED_ATTEMPTS));
        h.setClassifications(java.util.Map.of(), true);
        return h;
    }
}
