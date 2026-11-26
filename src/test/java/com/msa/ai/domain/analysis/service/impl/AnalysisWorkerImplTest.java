package com.msa.ai.domain.analysis.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.msa.ai.domain.analysis.client.VisionClient;
import com.msa.ai.domain.analysis.dto.response.AnalysisWorkResponse;
import com.msa.ai.domain.analysis.exception.*;
import com.msa.ai.domain.analysis.service.AnalysisJobService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Optional;
import java.util.UUID;

/** 실패 원인은 안전한 진단만 기록하고 기존 재시도를 유지합니다. */
@ExtendWith(OutputCaptureExtension.class)
class AnalysisWorkerImplTest {
    @Test
    void logsSafeDiagnosticAndRetries(CapturedOutput output) {
        var jobs = mock(AnalysisJobService.class);
        var vision = mock(VisionClient.class);
        var work = new AnalysisWorkResponse(UUID.randomUUID(), UUID.randomUUID(), null);
        when(jobs.claim()).thenReturn(Optional.of(work));
        when(vision.analyze(null)).thenThrow(
                new AnalysisException(AnalysisErrorCode.UNAVAILABLE, "vision.http.status=429"));
        assertThat(new AnalysisWorkerImpl(jobs, vision).runNext()).isTrue();
        verify(jobs).retry(work);
        verify(jobs, never()).complete(any(), any());
        assertThat(output.getOut()).contains(work.id().toString(), "diagnostic=vision.http.status=429");
    }

    @Test
    void excludesUnexpectedExceptionMessage(CapturedOutput output) {
        var jobs = mock(AnalysisJobService.class);
        var vision = mock(VisionClient.class);
        var work = new AnalysisWorkResponse(UUID.randomUUID(), UUID.randomUUID(), null);
        when(jobs.claim()).thenReturn(Optional.of(work));
        when(vision.analyze(null)).thenThrow(new IllegalStateException("secret-key private-image"));
        assertThat(new AnalysisWorkerImpl(jobs, vision).runNext()).isTrue();
        verify(jobs).retry(work);
        assertThat(output.getOut()).contains("diagnostic=analysis.unexpected")
                .doesNotContain("secret-key", "private-image");
    }
}
