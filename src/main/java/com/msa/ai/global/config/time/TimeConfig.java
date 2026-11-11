package com.msa.ai.global.config.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 작업 임대와 재시도를 UTC 서버 시각으로 판단하며 테스트에서 Clock을 교체할 수 있습니다. */
@Configuration
public class TimeConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
