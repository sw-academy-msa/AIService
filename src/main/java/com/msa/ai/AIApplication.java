package com.msa.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** AI 작업자와 결과 조회 API를 한 앱에 배치하며 구매 Saga를 소유하지 않습니다. */
@SpringBootApplication
public class AIApplication {
    public static void main(String[] args) {
        SpringApplication.run(AIApplication.class, args);
    }
}
