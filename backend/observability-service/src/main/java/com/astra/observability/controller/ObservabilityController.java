package com.astra.observability.controller;

import com.astra.observability.service.MetricsService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1/observability")
@RequiredArgsConstructor
public class ObservabilityController {

    private final MeterRegistry meterRegistry;
    private final MetricsService metricsService;

    @GetMapping("/health")
    public ResponseEntity<Object> health() {
        return ResponseEntity.ok(Map.of("status", "healthy", "service", "observability-service"));
    }

    @PostMapping("/metrics/record")
    public ResponseEntity<Object> recordMetrics(@RequestBody MetricsRequest request) {
        log.info("Recording metrics: provider={} model={}", request.provider, request.model);
        meterRegistry.counter("observability.metrics.recorded").increment();

        String reqId    = request.requestId != null ? request.requestId : UUID.randomUUID().toString();
        String provider = request.provider  != null ? request.provider  : "unknown";

        metricsService.record(
            reqId, provider, request.model,
            request.latencyMs,
            request.inputTokens  > 0 ? request.inputTokens  : request.tokens,
            request.outputTokens,
            request.costUsd,
            request.status != null ? request.status : "success",
            request.tenantId
        );

        return ResponseEntity.ok(Map.of(
            "status", "recorded",
            "request_id", reqId,
            "provider", provider
        ));
    }

    @GetMapping("/metrics")
    public ResponseEntity<Object> getMetrics() {
        return ResponseEntity.ok(metricsService.getAggregated());
    }

    static class MetricsRequest {
        public String requestId;
        public String provider;
        public String model;
        public long   latencyMs;
        public long   latency;          // legacy alias
        public int    inputTokens;
        public int    outputTokens;
        public int    tokens;           // legacy alias for inputTokens
        public double costUsd;
        public String status;
        public String tenantId;
    }
}
