package com.msa.ai.domain.analysis.client.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.msa.ai.domain.analysis.client.ImageContentService;
import com.msa.ai.domain.analysis.dto.response.AiMessages.*;
import com.msa.ai.domain.analysis.exception.AnalysisException;
import com.msa.ai.global.config.openai.VisionProperties;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** 로컬 HTTP 공급자 대역으로 Responses 요청 직렬화·이미지·응답 검증을 확인합니다. 유료 호출은 없습니다. */
class VisionClientImplTest {
    JsonMapper json = new JsonMapper();
    ImageContentService images = mock(ImageContentService.class);
    Requested request;

    @BeforeEach
    void setup() {
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
                                "티셔츠",
                                "상세 설명",
                                null,
                                null,
                                "의류",
                                "미판매 재고",
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

    String response(String code, String questionCode) {
        return json.writeValueAsString(
                Map.of(
                        "status",
                        "completed",
                        "output",
                        List.of(
                                Map.of(
                                        "type",
                                        "message",
                                        "content",
                                        List.of(
                                                Map.of(
                                                        "type",
                                                        "output_text",
                                                        "text",
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "categoryCode",
                                                                        code,
                                                                        "questions",
                                                                        List.of(
                                                                                Map.of(
                                                                                        "checkCode",
                                                                                        questionCode,
                                                                                        "question",
                                                                                        "소재를 알려주세요.",
                                                                                        "reason",
                                                                                        "설명에 없습니다."))))))))));
    }

    @Test
    void sendsImageAndFixedSchemaWithoutApprovalOrPrivateShippingData() throws Exception {
        var captured = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/responses",
                e -> {
                    captured.set(
                            new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    var bytes =
                            response("CLOTHING", "CLOTHING_MATERIAL")
                                    .getBytes(StandardCharsets.UTF_8);
                    e.getResponseHeaders().set("Content-Type", "application/json");
                    e.sendResponseHeaders(200, bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.start();
        try {
            when(images.dataUrl(any())).thenReturn("data:image/jpeg;base64,c3ludGhldGlj");
            var client =
                    new VisionClientImpl(
                            RestClient.builder()
                                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                                    .build(),
                            new VisionProperties(true, "synthetic-test-key", "gpt-6.1-sol"),
                            images,
                            json);
            var result = client.analyze(request);
            assertThat(result.questions()).hasSize(1);
            var body = json.readTree(captured.get());
            assertThat(body.path("model").asText()).isEqualTo("gpt-6.1-sol");
            assertThat(body.path("store").asBoolean()).isFalse();
            assertThat(body.at("/text/format/strict").asBoolean()).isTrue();
            assertThat(body.at("/input/0/content/1/type").asText()).isEqualTo("input_image");
            assertThat(captured.get())
                    .doesNotContain("shippingAddress", "sellerId", "synthetic-test-key");
            verify(images).dataUrl(request.input().images().getFirst());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsCategoryOrCheckOutsideFixedCatalog() {
        var client =
                new VisionClientImpl(
                        RestClient.create(),
                        new VisionProperties(true, "synthetic", "gpt-6.1-sol"),
                        images,
                        json);
        assertThatThrownBy(() -> client.parse(request, response("UNKNOWN", "CLOTHING_MATERIAL")))
                .isInstanceOf(AnalysisException.class);
        assertThatThrownBy(() -> client.parse(request, response("CLOTHING", "INVENTED_CHECK")))
                .isInstanceOf(AnalysisException.class);
        assertThatThrownBy(
                        () ->
                                client.parse(
                                        request,
                                        json.writeValueAsString(
                                                Map.of(
                                                        "status",
                                                        "incomplete",
                                                        "output",
                                                        List.of()))))
                .isInstanceOf(AnalysisException.class);
    }

    /** 공급자 enum 한도를 넘는 점검 기준도 전달하고 선택 가능한 카테고리 enum은 유지합니다. */
    @Test
    void expandedCatalogUsesBoundedSchemaAndStillSendsImages() throws Exception {
        var list = new ArrayList<Category>(request.categories());
        for (int n = 1; n < 255; n++) {
            var checks = new ArrayList<Check>();
            for (int j = 0; j < 4; j++)
                checks.add(new Check("SYNTHETIC_" + n + "_CHECK_" + j, "정보를 알려주세요."));
            list.add(new Category("SYNTHETIC_" + n, "합성 분류 " + n, checks));
        }
        var expanded =
                new Requested(
                        request.requestId(),
                        request.productId(),
                        request.sellerId(),
                        request.productVersion(),
                        request.fingerprint(),
                        request.occurredAt(),
                        request.input(),
                        list);
        var captured = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/responses",
                e -> {
                    captured.set(
                            new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    byte[] bytes =
                            response("CLOTHING", "CLOTHING_MATERIAL")
                                    .getBytes(StandardCharsets.UTF_8);
                    e.getResponseHeaders().set("Content-Type", "application/json");
                    e.sendResponseHeaders(200, bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.start();
        try {
            when(images.dataUrl(any())).thenReturn("data:image/jpeg;base64,c3ludGhldGlj");
            var client =
                    new VisionClientImpl(
                            RestClient.builder()
                                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                                    .build(),
                            new VisionProperties(true, "synthetic-key", "gpt-6.1-sol"),
                            images,
                            json);
            assertThat(client.analyze(expanded).recommendedCategory()).isEqualTo("CLOTHING");
            var body = json.readTree(captured.get());
            assertThat(body.at("/text/format/schema/properties/categoryCode/enum").size())
                    .isEqualTo(256);
            var check =
                    body.at("/text/format/schema/properties/questions/items/properties/checkCode");
            assertThat(check.path("type").asText()).isEqualTo("string");
            assertThat(check.has("enum")).isFalse();
            String input = body.at("/input/0/content/0/text").asText();
            assertThat(json.readTree(input).path("categoryCriteria").size()).isEqualTo(255);
            assertThat(body.at("/input/0/content/1/type").asText()).isEqualTo("input_image");
        } finally {
            server.stop(0);
        }
    }
}
