package com.msa.ai.global.config.openai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 운영 API 주소를 고정해 키가 임의 호스트로 전송되지 않게 합니다. 실제 호출은 명시적으로 활성화합니다. */
@Configuration
@EnableConfigurationProperties(VisionProperties.class)
public class VisionConfig {
    @Bean
    public RestClient openAiClient(VisionProperties p) {
        if (p.enabled()
                && (p.apiKey() == null
                        || p.apiKey().isBlank()
                        || p.model() == null
                        || p.model().isBlank()))
            throw new IllegalArgumentException("활성화된 이미지 분석의 키와 모델 설정이 필요합니다.");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(60000);
        return RestClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + (p.apiKey() == null ? "" : p.apiKey()))
                .build();
    }
}
