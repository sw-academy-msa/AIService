package com.msa.ai.global.config.campaign;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 모드 설정은 기존 환경 변수와 동일한 재시작 방식으로 적용합니다. */
@Configuration
@EnableConfigurationProperties({
    CampaignProperties.class,
    com.msa.ai.global.config.client.ClientProperties.class
})
public class CampaignConfig {
    @org.springframework.context.annotation.Bean(name = "campaignScheduler")
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler campaignScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("campaign-");
        return scheduler;
    }
}
