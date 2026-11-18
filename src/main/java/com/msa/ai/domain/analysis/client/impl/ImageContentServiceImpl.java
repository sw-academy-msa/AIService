package com.msa.ai.domain.analysis.client.impl;

import com.msa.ai.domain.analysis.client.ImageContentService;
import com.msa.ai.domain.analysis.dto.response.AiMessages.Image;
import com.msa.ai.domain.analysis.exception.*;
import com.msa.ai.global.config.storage.ImageProperties;

import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.util.Base64;

/** 단기 URL을 Kafka에 저장하지 않고 실행 시 불변 객체를 읽습니다. 다운로드 크기도 제한합니다. */
@Service
public class ImageContentServiceImpl implements ImageContentService {
    private final S3Client s3;
    private final ImageProperties properties;

    public ImageContentServiceImpl(S3Client s3, ImageProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    public String dataUrl(Image image) {
        if (properties.bucket() == null || properties.bucket().isBlank())
            throw new AnalysisException(AnalysisErrorCode.UNAVAILABLE);
        try (var stream =
                s3.getObject(
                        GetObjectRequest.builder()
                                .bucket(properties.bucket())
                                .key(image.objectKey())
                                .build())) {
            if (stream.response().contentLength() != image.sizeBytes()
                    || !image.contentType().equals(stream.response().contentType()))
                throw new AnalysisException(AnalysisErrorCode.INVALID);
            byte[] bytes = stream.readNBytes((int) image.sizeBytes() + 1);
            if (bytes.length != image.sizeBytes())
                throw new AnalysisException(AnalysisErrorCode.INVALID);
            return "data:"
                    + image.contentType()
                    + ";base64,"
                    + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception ex) {
            throw new AnalysisException(AnalysisErrorCode.UNAVAILABLE);
        }
    }
}
