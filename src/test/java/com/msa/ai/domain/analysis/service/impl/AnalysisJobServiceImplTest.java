package com.msa.ai.domain.analysis.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.msa.ai.domain.analysis.client.VisionClient;
import com.msa.ai.domain.analysis.dto.response.*;
import com.msa.ai.domain.analysis.dto.response.AiMessages.*;
import com.msa.ai.domain.analysis.exception.*;
import com.msa.ai.domain.analysis.repository.AnalysisJobRepository;
import com.msa.ai.domain.analysis.service.*;
import com.msa.ai.domain.outbox.repository.OutboxEventRepository;
import com.msa.ai.global.config.database.AiDatabaseTestSupport;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

/** 실제 DB에서 요청 중복·임대 만료·Outbox 원자성·판매자 소유권을 검증합니다. 실제 모델은 호출하지 않습니다. */
@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalysisJobServiceImplTest extends AiDatabaseTestSupport {
    @Autowired AnalysisJobService jobs;
    @Autowired AnalysisWorker worker;
    @Autowired AnalysisJobRepository repository;
    @Autowired OutboxEventRepository outbox;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;
    @MockitoBean VisionClient vision;
    Requested request;

    /** 실행 중인 MVC에서 생성한 계약을 UTF-8로 내보내며 저장된 계약과 JSON 구조를 비교합니다. */
    @Test
    void exportsOpenApiContract() throws Exception {
        var result =
                mvc.perform(get("/v3/api-docs").with(jwt())).andExpect(status().isOk()).andReturn();
        String generated =
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var output = java.nio.file.Path.of("build/generated/ai.openapi.json");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, generated, java.nio.charset.StandardCharsets.UTF_8);
        var snapshot = java.nio.file.Path.of("openapi/ai.openapi.json");
        var mapper = new tools.jackson.databind.json.JsonMapper();
        assertThat(mapper.readTree(generated))
                .isEqualTo(mapper.readTree(java.nio.file.Files.readString(snapshot)));
    }

    @BeforeEach
    void setup() {
        outbox.deleteAll();
        repository.deleteAll();
        reset(vision);
        var product = UUID.randomUUID();
        request =
                new Requested(
                        UUID.randomUUID(),
                        product,
                        UUID.randomUUID(),
                        1,
                        "a".repeat(64),
                        Instant.now(),
                        new Input(
                                "합성 상품",
                                "설명입니다.",
                                "합성 스토어",
                                "의류",
                                "잉여 재고 정리",
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
    }

    Completed result() {
        return new Completed(
                request.requestId(),
                request.productId(),
                request.sellerId(),
                request.fingerprint(),
                Outcome.RECOMMENDED,
                "CLOTHING",
                List.of(
                        new Question(
                                UUID.randomUUID(), null, "흠집이 있나요?", "사진 근거", "판매자 확인 필요")));
    }

    @Test
    // 최초 실행을 포함해 다섯 번 실패하면 영속 실패와 이벤트를 남기고 모델을 더 호출하지 않습니다.
    void stopsAfterFiveAttemptsAndReturnsFailureContract() throws Exception {
        jobs.accept(request);
        when(vision.analyze(any())).thenThrow(new AnalysisException(AnalysisErrorCode.UNAVAILABLE));
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(worker.runNext()).isTrue();
            assertThat(jobs.get(request.sellerId(), request.requestId()).status())
                    .isEqualTo(attempt == 5 ? "FAILED" : "RETRY");
            if (attempt < 5) tx.executeWithoutResult(s -> repository.findById(request.requestId()).orElseThrow()
                    .nextAttemptAt = Instant.now().minusSeconds(1));
        }
        assertThat(worker.runNext()).isFalse();
        verify(vision, times(5)).analyze(any());
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(jobs.get(request.sellerId(), request.requestId()).nextAttemptAt()).isNull();
        assertThat(outbox.findById(request.requestId()).orElseThrow().payload)
                .contains("FAILED", "AI_ANALYSIS_ATTEMPTS_EXHAUSTED");
        mvc.perform(get("/api/v1/ai/product-analyses/" + request.requestId())
                        .with(jwt().jwt(j -> j.subject(request.sellerId().toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_SELLER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.attempts").value(5))
                .andExpect(jsonPath("$.data.failureCode").value("AI_ANALYSIS_ATTEMPTS_EXHAUSTED"))
                .andExpect(jsonPath("$.data.result").isEmpty());
    }

    @Test
    // 다섯 번째 점유가 중단되거나 구버전이 이미 상한을 넘겼어도 여섯 번째 모델 실행은 하지 않습니다.
    void exhaustedExpiredLeaseOrLegacyRetryEndsWithoutAnotherClaim() {
        jobs.accept(request);
        tx.executeWithoutResult(s -> {
            var j = repository.findById(request.requestId()).orElseThrow();
            j.attempts = 5;
            j.status = "RUNNING";
            j.leaseUntil = Instant.now().minusSeconds(1);
        });
        assertThat(jobs.claim()).isEmpty();
        assertThat(jobs.get(request.sellerId(), request.requestId()).status()).isEqualTo("FAILED");
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(jobs.claim()).isEmpty();
    }

    @Test
    void duplicateRequestDoesNotDuplicateJob() {
        jobs.accept(request);
        jobs.accept(request);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(jobs.claim()).isPresent();
        assertThat(jobs.claim()).isEmpty();
    }

    /** sourceUrl이 있던 이전 저장 요청이 다시 와도 같은 요청으로 보고 작업을 늘리지 않습니다. */
    @Test
    void legacyStoredRequestWithSourceUrlIsSameRequest() {
        jobs.accept(request);
        tx.executeWithoutResult(
                s -> {
                    var job = repository.findById(request.requestId()).orElseThrow();
                    job.requestPayload =
                            job.requestPayload.replace("\"name\":", "\"sourceUrl\":null,\"name\":");
                });
        assertThat(repository.findById(request.requestId()).orElseThrow().requestPayload)
                .contains("sourceUrl");
        jobs.accept(request);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(jobs.claim().orElseThrow().request()).isEqualTo(request);
    }

    @Test
    void completedResultAndOutboxAreIdempotent() {
        jobs.accept(request);
        var work = jobs.claim().orElseThrow();
        var result = result();
        jobs.complete(work, result);
        jobs.complete(work, result);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(jobs.get(request.sellerId(), request.requestId()).status())
                .isEqualTo("COMPLETED");
        assertThat(jobs.get(request.sellerId(), request.requestId()).result()).isEqualTo(result);
    }

    @Test
    void expiredLeaseCannotWriteResultOrOutbox() {
        jobs.accept(request);
        var work = jobs.claim().orElseThrow();
        tx.executeWithoutResult(
                s ->
                        repository.findById(work.id()).orElseThrow().leaseUntil =
                                Instant.now().minusSeconds(1));
        jobs.complete(work, result());
        assertThat(outbox.count()).isZero();
        assertThat(repository.findById(work.id()).orElseThrow().resultPayload).isNull();
        var newWork = jobs.claim().orElseThrow();
        assertThat(newWork.leaseToken()).isNotEqualTo(work.leaseToken());
        jobs.complete(work, result());
        assertThat(outbox.count()).isZero();
    }

    @Test
    void providerFailureLeavesDurableRetryAndCanRecover() {
        jobs.accept(request);
        when(vision.analyze(any())).thenThrow(new AnalysisException(AnalysisErrorCode.UNAVAILABLE));
        assertThat(worker.runNext()).isTrue();
        assertThat(jobs.get(request.sellerId(), request.requestId()).status()).isEqualTo("RETRY");
        assertThat(outbox.count()).isZero();
        tx.executeWithoutResult(
                s ->
                        repository.findById(request.requestId()).orElseThrow().nextAttemptAt =
                                Instant.now().minusSeconds(1));
        doReturn(result()).when(vision).analyze(any());
        assertThat(worker.runNext()).isTrue();
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void arbitraryObjectKeyAndMissingImagesAreRejected() {
        var input = request.input();
        var bad =
                new Requested(
                        request.requestId(),
                        request.productId(),
                        request.sellerId(),
                        1,
                        request.fingerprint(),
                        Instant.now(),
                        new Input(
                                input.name(),
                                input.description(),
                                null,
                                null,
                                input.disposalReason(),
                                List.of(
                                        new Image(
                                                "https://example.com/private", "image/jpeg", 10))),
                        request.categories());
        assertThatThrownBy(() -> jobs.accept(bad)).isInstanceOf(AnalysisException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    void httpApiEnforcesRoleAndOwnerAndUsesEnvelope() throws Exception {
        jobs.accept(request);
        String path = "/api/v1/ai/product-analyses/" + request.requestId();
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(
                        get(path)
                                .with(
                                        jwt().jwt(j -> j.subject(request.sellerId().toString()))
                                                .authorities(
                                                        new SimpleGrantedAuthority("ROLE_BUYER"))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get(path)
                                .with(
                                        jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                                                .authorities(
                                                        new SimpleGrantedAuthority("ROLE_SELLER"))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get(path)
                                .with(
                                        jwt().jwt(j -> j.subject(request.sellerId().toString()))
                                                .authorities(
                                                        new SimpleGrantedAuthority("ROLE_SELLER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON_SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    /** 확장된 허용 목록을 영속 접수하면서 무제한 목록 입력은 기존처럼 거부합니다. */
    @Test
    void acceptsExpandedCatalogAndRejectsUnboundedCatalog() {
        var categories =
                java.util.stream.IntStream.range(0, 255)
                        .mapToObj(
                                i ->
                                        new Category(
                                                "SYNTHETIC_" + i,
                                                "합성 분류 " + i,
                                                List.of(
                                                        new Check(
                                                                "SYNTHETIC_CHECK_" + i,
                                                                "규격을 알려주세요."))))
                        .toList();
        var expanded =
                new Requested(
                        UUID.randomUUID(),
                        request.productId(),
                        request.sellerId(),
                        request.productVersion(),
                        request.fingerprint(),
                        request.occurredAt(),
                        request.input(),
                        categories);
        jobs.accept(expanded);
        assertThat(repository.findById(expanded.requestId())).isPresent();
        var tooMany =
                java.util.stream.IntStream.range(0, 513)
                        .mapToObj(
                                i -> new Category("SYNTHETIC_" + i, "합성 분류 " + i, List.<Check>of()))
                        .toList();
        var rejected =
                new Requested(
                        UUID.randomUUID(),
                        request.productId(),
                        request.sellerId(),
                        request.productVersion(),
                        request.fingerprint(),
                        request.occurredAt(),
                        request.input(),
                        tooMany);
        assertThatThrownBy(() -> jobs.accept(rejected)).isInstanceOf(AnalysisException.class);
        assertThat(repository.findById(rejected.requestId())).isEmpty();
    }
}
