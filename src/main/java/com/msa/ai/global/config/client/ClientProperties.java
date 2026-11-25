package com.msa.ai.global.config.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 내부 Account 호출 설정과 별도 AI 서비스 자격 증명을 보관합니다. */
@ConfigurationProperties("ai.clients")
public record ClientProperties(
        String scheme,
        String account,
        String ai,
        String productQuery,
        String clientSecret,
        String serviceSubject) {
    public ClientProperties {
        scheme = scheme == null || scheme.isBlank() ? "https" : scheme;
    }

    @Override
    public String toString() {
        return "ClientProperties[REDACTED]";
    }
}
