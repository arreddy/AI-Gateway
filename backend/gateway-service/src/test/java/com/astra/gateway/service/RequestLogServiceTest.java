package com.astra.gateway.service;

import com.astra.gateway.entity.RequestLog;
import com.astra.gateway.repository.RequestLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RequestLogServiceTest {

    @Mock
    private RequestLogRepository requestLogRepository;

    private RequestLogService requestLogService;

    @BeforeEach
    void setUp() {
        requestLogService = new RequestLogService(requestLogRepository);
    }

    @Test
    void log_success_savesRequestLog() {
        when(requestLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        requestLogService.log("claude-sonnet-4-6", "claude-sonnet-4-6", "success", 100, 50, 200, null);

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(requestLogRepository, timeout(1000)).save(captor.capture());

        RequestLog saved = captor.getValue();
        assertThat(saved.getModelRequested()).isEqualTo("claude-sonnet-4-6");
        assertThat(saved.getStatus()).isEqualTo("success");
        assertThat(saved.getInputTokens()).isEqualTo(100);
        assertThat(saved.getOutputTokens()).isEqualTo(50);
        assertThat(saved.getTotalTokens()).isEqualTo(150);
        assertThat(saved.getLatencyMs()).isEqualTo(200);
    }

    @Test
    void log_withError_savesErrorMessage() {
        when(requestLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        requestLogService.log("gpt-4o", "gpt-4o", "failed", 0, 0, 50, "Timeout occurred");

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(requestLogRepository, timeout(1000)).save(captor.capture());
        assertThat(captor.getValue().getErrorMessage()).isEqualTo("Timeout occurred");
    }

    @Test
    void log_repositoryThrows_doesNotPropagate() {
        doThrow(new RuntimeException("DB down")).when(requestLogRepository).save(any());

        // Must not throw — @Async swallows the exception
        requestLogService.log("gpt-4o", "gpt-4o", "success", 10, 5, 100, null);
    }

    @Test
    void log_gptModel_estimatesHigherInputCost() {
        when(requestLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        requestLogService.log("gpt-4o", "gpt-4o", "success", 1_000_000, 0, 100, null);
        requestLogService.log("claude-sonnet-4-6", "claude-sonnet-4-6", "success", 1_000_000, 0, 100, null);

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(requestLogRepository, timeout(2000).times(2)).save(captor.capture());

        RequestLog gptLog    = captor.getAllValues().get(0);
        RequestLog claudeLog = captor.getAllValues().get(1);

        assertThat(gptLog.getTotalCost()).isGreaterThan(claudeLog.getTotalCost());
    }

    @Test
    void log_zeroTokens_costIsZero() {
        when(requestLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        requestLogService.log("claude-sonnet-4-6", "claude-sonnet-4-6", "success", 0, 0, 50, null);

        ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
        verify(requestLogRepository, timeout(1000)).save(captor.capture());
        assertThat(captor.getValue().getTotalCost()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void log_nullModel_doesNotThrow() {
        when(requestLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        requestLogService.log(null, null, "success", 10, 5, 100, null);
        verify(requestLogRepository, timeout(1000)).save(any());
    }
}
