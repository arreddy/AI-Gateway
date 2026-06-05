package com.astra.gateway.service;

import com.astra.gateway.entity.RequestLog;
import com.astra.gateway.repository.RequestLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class RequestLogService {

    private final RequestLogRepository requestLogRepository;

    /**
     * Persist a completed request log asynchronously so the caller is not blocked.
     * Spring's @Async task executor handles the thread; any DB error is logged only.
     */
    @Async
    public void log(String modelRequested, String modelUsed, String status,
                    int inputTokens, int outputTokens, int latencyMs, String errorMessage) {
        try {
            RequestLog entry = new RequestLog();
            entry.setModelRequested(modelRequested);
            entry.setModelUsed(modelUsed);
            entry.setStatus(status);
            entry.setInputTokens(inputTokens);
            entry.setOutputTokens(outputTokens);
            entry.setTotalTokens(inputTokens + outputTokens);
            entry.setLatencyMs(latencyMs);
            entry.setErrorMessage(errorMessage);
            entry.setTotalCost(estimateCost(modelUsed, inputTokens, outputTokens));

            requestLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("Failed to persist request log: {}", e.getMessage());
        }
    }

    private BigDecimal estimateCost(String model, int inputTokens, int outputTokens) {
        // Approximate pricing per 1M tokens (USD)
        double inputPricePerMtok  = model != null && model.startsWith("gpt") ? 5.00  : 3.00;
        double outputPricePerMtok = model != null && model.startsWith("gpt") ? 15.00 : 15.00;
        double cost = (inputTokens / 1_000_000.0 * inputPricePerMtok)
                    + (outputTokens / 1_000_000.0 * outputPricePerMtok);
        return BigDecimal.valueOf(cost).setScale(8, java.math.RoundingMode.HALF_UP);
    }
}
