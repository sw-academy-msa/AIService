package com.msa.ai.global.config.analytics;

import jakarta.validation.constraints.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.ZoneId;

/** 분석을 켰을 때만 Google 설정을 요구하며 비용과 메모리 상한을 명시합니다. */
@Validated
@ConfigurationProperties("ai.ga")
public record GaProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String projectId,
        @DefaultValue("") String dataset,
        @DefaultValue("") String location,
        @DefaultValue("Asia/Seoul") String zone,
        @DefaultValue("30") @Min(1) @Max(365) int lookbackDays,
        @DefaultValue("1073741824") @Min(1) long maximumBytesBilled,
        @DefaultValue("100000") @Min(1) @Max(1000000) int maximumRows) {
    @AssertTrue(message = "GA 활성화 시 유효한 project/dataset/location과 타임존이 필요합니다.")
    public boolean isConfigurationValid() {
        try {
            ZoneId.of(zone);
        } catch (Exception e) {
            return false;
        }
        return !enabled
                || (projectId != null
                        && projectId.matches("[a-z][a-z0-9-]{4,61}[a-z0-9]")
                        && dataset != null
                        && dataset.matches("analytics_[0-9]+")
                        && location != null
                        && location.matches("[A-Za-z0-9-]+"));
    }
}
