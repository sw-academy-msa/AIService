package com.msa.ai.domain.behavior.consumer;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.msa.ai.domain.behavior.exception.*;
import com.msa.ai.domain.behavior.service.BehaviorService;
import com.msa.ai.global.config.database.AiDatabaseTestSupport;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 실제 Kafka에서 계약 오류 즉시 격리와 일시 장애 네 번 시도 후 DLT를 검증합니다. */
@SpringBootTest(properties = {"ai.behavior.lifecycle-enabled=true"})
@ActiveProfiles("test")
class BehaviorMessagingIntegrationTest extends AiDatabaseTestSupport {
    static final String TOPIC = "stockdrop.orders.lifecycle.v1";
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static {
        KAFKA.start();
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(
                            List.of(
                                    new NewTopic(TOPIC, 1, (short) 1),
                                    new NewTopic(TOPIC + ".DLT", 1, (short) 1)))
                    .all()
                    .get(20, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry p) {
        p.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired KafkaTemplate<String, byte[]> kafka;
    @MockitoBean BehaviorService service;

    @Test
    void invalidContractAndTransientFailureHaveBoundedAttempts() throws Exception {
        byte[] invalid = "invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                temporary = "temporary".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        doThrow(new BehaviorException(BehaviorErrorCode.INVALID))
                .when(service)
                .lifecycle(eq("invalid"), any());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic"))
                .when(service)
                .lifecycle(eq("temporary"), any());
        kafka.send(TOPIC, "invalid", invalid).get(10, TimeUnit.SECONDS);
        kafka.send(TOPIC, "temporary", temporary).get(10, TimeUnit.SECONDS);
        var keys = new HashSet<String>();
        try (var consumer =
                new KafkaConsumer<String, byte[]>(
                        Map.of(
                                "bootstrap.servers",
                                KAFKA.getBootstrapServers(),
                                "group.id",
                                "behavior-dlt-" + UUID.randomUUID(),
                                "auto.offset.reset",
                                "earliest"),
                        new StringDeserializer(),
                        new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(TOPIC + ".DLT"));
            await().atMost(Duration.ofSeconds(30))
                    .until(
                            () -> {
                                for (var record : consumer.poll(Duration.ofMillis(300)))
                                    keys.add(record.key());
                                return keys.containsAll(Set.of("invalid", "temporary"));
                            });
        }
        verify(service, times(1)).lifecycle(eq("invalid"), any());
        verify(service, times(4)).lifecycle(eq("temporary"), any());
    }
}
