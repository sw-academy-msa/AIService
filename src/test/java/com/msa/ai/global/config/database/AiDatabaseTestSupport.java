package com.msa.ai.global.config.database;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 개발 일반 DB를 건드리지 않는 실제 PostgreSQL 테스트 환경입니다. */
public abstract class AiDatabaseTestSupport {
    static final PostgreSQLContainer DB =
            new PostgreSQLContainer("postgres:17-alpine").withInitScript("db/ai-schema.sql");

    static {
        DB.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }
}
