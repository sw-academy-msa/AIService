package com.msa.ai.global.config.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Product 이미지 버킷의 읽기 권한만 사용합니다. 업로드·삭제 권한은 AI에 부여하지 않습니다. */
@ConfigurationProperties("ai.images")
public record ImageProperties(String bucket, String region, boolean local, String endpoint) {}
