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
                                null,
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
                "CLOTHING",
                List.of(new Question(UUID.randomUUID(), "CLOTHING_MATERIAL", "소재를 알려주세요.", "누락")));
    }

    @Test
    void duplicateRequestDoesNotDuplicateJob() {
        jobs.accept(request);
        jobs.accept(request);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(jobs.claim()).isPresent();
        assertThat(jobs.claim()).isEmpty();
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
}
