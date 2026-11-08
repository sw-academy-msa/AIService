package com.msa.ai.domain.analysis.consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.msa.ai.domain.analysis.client.VisionClient;
import com.msa.ai.domain.analysis.dto.response.AiMessages;
import com.msa.ai.domain.analysis.dto.response.AiMessages.*;
import com.msa.ai.domain.analysis.repository.AnalysisJobRepository;
import com.msa.ai.domain.analysis.service.AnalysisWorker;
import com.msa.ai.domain.outbox.repository.OutboxEventRepository;
import com.msa.ai.global.config.database.AiDatabaseTestSupport;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.kafka.KafkaContainer;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** 실제 Kafka와 PostgreSQL로 중복 요청 수신과 스케줄 기반 결과 Outbox 발행을 검증합니다. */
@SpringBootTest(
        properties = {
            "ai.consumer-enabled=true",
            "ai.outbox.publisher-enabled=true",
            "ai.outbox.poll-ms=50"
        })
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AnalysisMessagingIntegrationTest extends AiDatabaseTestSupport {
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static {
        KAFKA.start();
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(
                            List.of(
                                    new NewTopic(AiMessages.REQUEST_TOPIC, 1, (short) 1),
                                    new NewTopic(AiMessages.RESULT_TOPIC, 1, (short) 1)))
                    .all()
                    .get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired KafkaTemplate<String, byte[]> kafka;
    @Autowired AnalysisJobRepository jobs;
    @Autowired OutboxEventRepository outbox;
    @Autowired AnalysisWorker worker;
    @Autowired JsonMapper json;
    @MockitoBean VisionClient vision;

    @Test
    void requestIsPersistedOnceAndScheduledPublisherDeliversResult() throws Exception {
        var product = UUID.randomUUID();
        var request =
                new Requested(
                        UUID.randomUUID(),
                        product,
                        UUID.randomUUID(),
                        1,
                        "a".repeat(64),
                        Instant.now(),
                        new Input(
                                "합성 상품",
                                "설명",
                                null,
                                null,
                                "의류",
                                "재고 정리",
                                List.of(
                                        new Image(
                                                "product-images/"
                                                        + product
                                                        + "/"
                                                        + UUID.randomUUID(),
                                                "image/jpeg",
                                                10))),
                        List.of(
                                new Category(
                                        "CLOTHING",
                                        "의류",
                                        List.of(new Check("CLOTHING_MATERIAL", "소재를 알려주세요.")))));
        byte[] payload = json.writeValueAsBytes(request);
        kafka.send(AiMessages.REQUEST_TOPIC, product.toString(), payload).get(15, TimeUnit.SECONDS);
        kafka.send(AiMessages.REQUEST_TOPIC, product.toString(), payload).get(15, TimeUnit.SECONDS);
        waitUntil(() -> jobs.findById(request.requestId()).isPresent());
        var result =
                new Completed(
                        request.requestId(),
                        product,
                        request.sellerId(),
                        request.fingerprint(),
                        "CLOTHING",
                        List.of(
                                new Question(
                                        UUID.randomUUID(),
                                        "CLOTHING_MATERIAL",
                                        "소재를 알려주세요.",
                                        "설명에 없습니다.")));
        when(vision.analyze(any())).thenReturn(result);
        assertThat(worker.runNext()).isTrue();
        // publishNext를 직접 호출하지 않고 실제 스케줄이 ack와 publishedAt을 기록할 때까지 확인합니다.
        waitUntil(
                () ->
                        outbox.findById(request.requestId())
                                .map(e -> e.publishedAt != null)
                                .orElse(false));
        try (var consumer =
                new KafkaConsumer<String, byte[]>(
                        Map.of(
                                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                KAFKA.getBootstrapServers(),
                                ConsumerConfig.GROUP_ID_CONFIG,
                                "ai-result-test-" + UUID.randomUUID(),
                                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                                "earliest",
                                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                                StringDeserializer.class,
                                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                                ByteArrayDeserializer.class))) {
            consumer.subscribe(List.of(AiMessages.RESULT_TOPIC));
            boolean found = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (!found && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(100))) {
                    var received = json.readValue(record.value(), Completed.class);
                    if (received.requestId().equals(request.requestId())) {
                        assertThat(received).isEqualTo(result);
                        assertThat(record.key()).isEqualTo(product.toString());
                        found = true;
                    }
                }
            }
            assertThat(found).isTrue();
        }
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    private void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
