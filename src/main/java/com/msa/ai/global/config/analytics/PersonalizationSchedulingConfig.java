package com.msa.ai.global.config.analytics;

import com.msa.ai.domain.personalization.service.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** GA 외부 조회와 후보 분할 처리를 서로 분리하고 기존 이미지 분석·Outbox 스레드를 점유하지 않습니다. */
@Configuration
public class PersonalizationSchedulingConfig {
    @Bean(name = "gaSyncScheduler")
    public ThreadPoolTaskScheduler gaSyncScheduler() {
        return scheduler("ga-sync-");
    }

    @Bean(name = "recommendationScheduler")
    public ThreadPoolTaskScheduler recommendationScheduler() {
        return scheduler("recommendation-");
    }

    private ThreadPoolTaskScheduler scheduler(String prefix) {
        var s = new ThreadPoolTaskScheduler();
        s.setPoolSize(1);
        s.setThreadNamePrefix(prefix);
        return s;
    }

    @Bean
    @ConditionalOnProperty(name = "ai.ga.enabled", havingValue = "true")
    public GaPoll gaPoll(GaSyncWorker worker) {
        return new GaPoll(worker);
    }

    @Bean
    @ConditionalOnProperty(name = "ai.personalization.enabled", havingValue = "true")
    public RecommendationPoll recommendationPoll(RecommendationService service) {
        return new RecommendationPoll(service);
    }

    public static class GaPoll {
        private final GaSyncWorker worker;

        GaPoll(GaSyncWorker worker) {
            this.worker = worker;
        }

        @Scheduled(fixedDelayString = "${ai.ga.poll-ms:60000}", scheduler = "gaSyncScheduler")
        public void poll() {
            worker.runNext();
        }
    }

    public static class RecommendationPoll {
        private final RecommendationService service;

        RecommendationPoll(RecommendationService service) {
            this.service = service;
        }

        @Scheduled(
                fixedDelayString = "${ai.personalization.poll-ms:1000}",
                scheduler = "recommendationScheduler")
        public void poll() {
            service.processNext();
        }
    }
}
