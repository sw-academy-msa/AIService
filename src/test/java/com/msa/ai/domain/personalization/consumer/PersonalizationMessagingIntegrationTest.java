package com.msa.ai.domain.personalization.consumer;

import static org.assertj.core.api.Assertions.*;

import com.msa.ai.domain.outbox.repository.OutboxEventRepository;
import com.msa.ai.domain.personalization.dto.response.*;
import com.msa.ai.domain.personalization.dto.response.PersonalizationMessages.*;
import com.msa.ai.domain.personalization.repository.*;
import com.msa.ai.domain.personalization.service.*;
import com.msa.ai.global.config.database.AiDatabaseTestSupport;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.kafka.KafkaContainer;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 실제 Kafka의 최초 공개 수신부터 자동 후보 Outbox 발행까지 확인하며 Google과 실제 알림 발송은 호출하지 않습니다. */
@SpringBootTest(
        properties = {
            "ai.personalization.enabled=true",
            "ai.personalization.poll-ms=50",
            "ai.outbox.publisher-enabled=true",
            "ai.outbox.poll-ms=50"
        })
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PersonalizationMessagingIntegrationTest extends AiDatabaseTestSupport {
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static {
        KAFKA.start();
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(
                            List.of(
                                    new NewTopic(
                                            PersonalizationMessages.PUBLICATION_TOPIC,
                                            1,
                                            (short) 1),
                                    new NewTopic(
                                            PersonalizationMessages.CANDIDATE_TOPIC, 1, (short) 1)))
                    .all()
                    .get(20, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired AnalyticsIdentityService identity;
    @Autowired GaSyncService sync;
    @Autowired RecommendationCandidateRepository candidates;
    @Autowired OutboxEventRepository outbox;
    @Autowired KafkaTemplate<String, byte[]> kafka;
    @Autowired JsonMapper json;

    @Test
    void duplicatePublicationProducesOneDurableKafkaCandidate() throws Exception {
        var account = UUID.randomUUID();
        var analytics = identity.enroll(account).analyticsId();
        var work = sync.claim().orElseThrow();
        sync.complete(
                work,
                List.of(
                        GaViewResponse.builder()
                                .analyticsId(analytics)
                                .categoryName("문구")
                                .views(3)
                                .lastViewedAt(
                                        work.through()
                                                .atTime(12, 0)
                                                .atZone(ZoneId.of("Asia/Seoul"))
                                                .toInstant())
                                .build()));
        var e =
                Published.builder()
                        .eventId(UUID.randomUUID())
                        .schemaVersion(1)
                        .productId(UUID.randomUUID())
                        .sellerId(UUID.randomUUID())
                        .productName("합성 상품")
                        .categoryCode("STATIONERY")
                        .categoryName("문구")
                        .publishedAt(Instant.now())
                        .startsAt(Instant.now().plusSeconds(3600))
                        .endsAt(Instant.now().plusSeconds(14400))
                        .build();
        kafka.send(
                        PersonalizationMessages.PUBLICATION_TOPIC,
                        e.productId().toString(),
                        json.writeValueAsBytes(e))
                .get(15, TimeUnit.SECONDS);
        kafka.send(
                        PersonalizationMessages.PUBLICATION_TOPIC,
                        e.productId().toString(),
                        json.writeValueAsBytes(e))
                .get(15, TimeUnit.SECONDS);
        try (var consumer =
                new KafkaConsumer<String, byte[]>(
                        Map.of(
                                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                KAFKA.getBootstrapServers(),
                                ConsumerConfig.GROUP_ID_CONFIG,
                                "recommendation-test-" + UUID.randomUUID(),
                                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                                "earliest",
                                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                                StringDeserializer.class,
                                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                                ByteArrayDeserializer.class))) {
            consumer.subscribe(List.of(PersonalizationMessages.CANDIDATE_TOPIC));
            Candidate received = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
            while (received == null && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(100))) {
                    var result = json.readValue(record.value(), Candidate.class);
                    if (result.productId().equals(e.productId())) {
                        received = result;
                        break;
                    }
                }
            }
            assertThat(received).isNotNull();
            assertThat(received.accountId()).isEqualTo(account);
            assertThat(received.analyticsId()).isEqualTo(analytics);
            assertThat(candidates.count()).isEqualTo(1);
            UUID eventId = received.eventId();
            while (outbox.findById(eventId).orElseThrow().publishedAt == null
                    && System.nanoTime() < deadline) Thread.sleep(50);
            assertThat(outbox.findById(eventId).orElseThrow().publishedAt).isNotNull();
        }
    }
}
