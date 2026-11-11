package com.msa.ai.global.config.analytics;

import com.google.cloud.bigquery.*;
import com.google.cloud.http.HttpTransportOptions;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

/** Google 자격은 ADC로 읽으며 키 JSON을 코드나 설정값에 복사하지 않습니다. 비활성 시 자격 없이 시작합니다. */
@Configuration
@EnableConfigurationProperties(GaProperties.class)
public class BigQueryConfig {
    @Bean
    @ConditionalOnProperty(name = "ai.ga.enabled", havingValue = "true")
    public BigQuery gaBigQuery(GaProperties properties) {
        return BigQueryOptions.newBuilder()
                .setProjectId(properties.projectId())
                .setTransportOptions(
                        HttpTransportOptions.newBuilder()
                                .setConnectTimeout(10000)
                                .setReadTimeout(60000)
                                .build())
                .build()
                .getService();
    }
}
