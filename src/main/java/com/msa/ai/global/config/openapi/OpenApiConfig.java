package com.msa.ai.global.config.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 일반 Gateway를 외부 진입점으로 안내하며 직접 서비스 주소는 로컬 개발용으로 구분합니다. */
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI aiOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("STOCKDROP AI API")
                                .version("v1")
                                .description("판매자 상품 이미지 기반 카테고리 추천과 등록정보 점검 결과 조회 API"))
                .servers(
                        List.of(
                                new Server()
                                        .url("http://localhost:8000")
                                        .description("로컬 일반 Gateway"),
                                new Server()
                                        .url("http://localhost:8086")
                                        .description("로컬 AI 직접 접근")));
    }
}
