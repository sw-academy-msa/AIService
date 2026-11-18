package com.msa.ai.global.config.scheduling;

import com.msa.ai.domain.analysis.service.AnalysisWorker;
import com.msa.ai.domain.outbox.service.OutboxPublisher;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

/** 구매 처리와 독립된 제한된 작업자입니다. 모델 실행과 발행 스케줄을 별도 스레드로 분리합니다. */
@Configuration
@EnableScheduling
public class WorkerConfig {
    @Bean
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler() {
        var s = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        s.setPoolSize(2);
        s.setThreadNamePrefix("ai-worker-");
        return s;
    }

    @Bean
    @ConditionalOnProperty(name = "ai.worker-enabled", havingValue = "true")
    public WorkSchedule workSchedule(AnalysisWorker worker) {
        return new WorkSchedule(worker);
    }

    @Bean
    public PublishSchedule publishSchedule(ObjectProvider<OutboxPublisher> publisher) {
        return new PublishSchedule(publisher);
    }

    public static class WorkSchedule {
        private final AnalysisWorker worker;

        WorkSchedule(AnalysisWorker worker) {
            this.worker = worker;
        }

        @Scheduled(fixedDelayString = "${ai.poll-ms:2000}")
        public void run() {
            worker.runNext();
        }
    }

    public static class PublishSchedule {
        private final ObjectProvider<OutboxPublisher> publisher;

        PublishSchedule(ObjectProvider<OutboxPublisher> publisher) {
            this.publisher = publisher;
        }

        @Scheduled(fixedDelayString = "${ai.outbox.poll-ms:1000}")
        public void run() {
            var p = publisher.getIfAvailable();
            if (p != null) p.publishNext();
        }
    }
}
