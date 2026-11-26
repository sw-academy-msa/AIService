package com.msa.ai.domain.analysis.client.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.msa.ai.domain.analysis.dto.response.AiMessages.Image;
import com.msa.ai.domain.analysis.exception.AnalysisException;
import com.msa.ai.domain.analysis.exception.AnalysisErrorCode;
import com.msa.ai.global.config.storage.ImageProperties;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;

/** 모델에 전달하기 전에 S3의 실제 메타데이터와 원본 크기를 재검증합니다. */
class ImageContentServiceImplTest {
    S3Client s3 = mock(S3Client.class);
    ImageContentServiceImpl service =
            new ImageContentServiceImpl(
                    s3, new ImageProperties("synthetic-bucket", "ap-northeast-2", false, null));
    Image image = new Image("product-images/synthetic/image", "image/jpeg", 3);

    ResponseInputStream<GetObjectResponse> response(long size, byte[] bytes) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(size).contentType("image/jpeg").build(),
                AbortableInputStream.create(new ByteArrayInputStream(bytes)));
    }

    @Test
    void readsConfiguredBucketAndReturnsImageDataUrl() {
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(response(3, new byte[] {1, 2, 3}));
        assertThat(service.dataUrl(image)).isEqualTo("data:image/jpeg;base64,AQID");
        verify(s3)
                .getObject(
                        GetObjectRequest.builder()
                                .bucket("synthetic-bucket")
                                .key(image.objectKey())
                                .build());
    }

    @Test
    void rejectsChangedMetadataOrOversizedStream() {
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(response(4, new byte[] {1, 2, 3, 4}));
        assertThatThrownBy(() -> service.dataUrl(image)).isInstanceOfSatisfying(
                AnalysisException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(AnalysisErrorCode.INVALID);
                    assertThat(ex.diagnostic()).isEqualTo("image.metadata_mismatch");
                });
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(response(3, new byte[] {1, 2, 3, 4}));
        assertThatThrownBy(() -> service.dataUrl(image)).isInstanceOfSatisfying(
                AnalysisException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(AnalysisErrorCode.INVALID);
                    assertThat(ex.diagnostic()).isEqualTo("image.size_mismatch");
                });
    }

    /** S3 거절과 읽기 오류는 원문이나 객체 경로 없이 구분합니다. */
    @Test
    void reportsSafeS3StatusAndReadFailure() {
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(403)
                        .message("secret-key product-images/private").build());
        assertThatThrownBy(() -> service.dataUrl(image)).isInstanceOfSatisfying(
                AnalysisException.class, ex -> {
                    assertThat(ex.diagnostic()).isEqualTo("image.s3.status=403");
                    assertThat(ex.getMessage()).doesNotContain("secret-key", "private");
                    assertThat(ex.getCause()).isNull();
                });
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenThrow(new IllegalStateException("secret-key"));
        assertThatThrownBy(() -> service.dataUrl(image)).isInstanceOfSatisfying(
                AnalysisException.class, ex -> assertThat(ex.diagnostic())
                        .isEqualTo("image.read.cause=IllegalStateException"));
    }
}
