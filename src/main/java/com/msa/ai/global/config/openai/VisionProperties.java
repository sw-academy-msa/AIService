package com.msa.ai.global.config.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 키는 환경변수로만 주입하며 record의 문자열 표현에도 노출하지 않습니다. */
@ConfigurationProperties("ai.vision")
public record VisionProperties(boolean enabled, String apiKey, String model) {
    @Override
    public String toString() {
        return "VisionProperties[REDACTED]";
    }
}
