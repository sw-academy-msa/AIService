package com.msa.ai.global.config.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.time.Duration;

/** 로컬만 S3Mock 고정 자격을 사용하고 운영은 IAM 기본 자격 공급자를 사용합니다. */
@Configuration
@EnableConfigurationProperties(ImageProperties.class)
public class ImageConfig {
    @Bean
    public S3Client analysisS3(ImageProperties p) {
        var b =
                S3Client.builder()
                        .region(Region.of(p.region()))
                        .httpClientBuilder(
                                UrlConnectionHttpClient.builder()
                                        .connectionTimeout(Duration.ofSeconds(5))
                                        .socketTimeout(Duration.ofSeconds(15)));
        if (p.local()) {
            if (p.endpoint() == null || p.endpoint().isBlank())
                throw new IllegalArgumentException("로컬 이미지 endpoint가 필요합니다.");
            b.endpointOverride(URI.create(p.endpoint()))
                    .forcePathStyle(true)
                    .credentialsProvider(
                            StaticCredentialsProvider.create(
                                    AwsBasicCredentials.create("local", "local")));
        }
        return b.build();
    }
}
