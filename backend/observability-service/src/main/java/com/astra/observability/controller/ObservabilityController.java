package com.astra.observability.controller;

import com.astra.observability.service.MetricsService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1/observability")
public class ObservabilityController {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private MetricsService metricsService;

    @GetMapping("/health")
    public ResponseEntity<Object> health() {
        return ResponseEntity.ok(Map.of("status", "healthy", "service", "observability-service"));
    }

    @PostMapping("/metrics/record")
    public ResponseEntity<Object> recordMetrics(@RequestBody MetricsRequest request) {
        log.info("Recording metrics for provider: {}", request.provider);
        meterRegistry.counter("observability.metrics.recorded").increment();

        String reqId = request.requestId != null ? request.requestId : UUID.randomUUID().toString();
        String provider = request.provider != null ? request.provider : "unknown";
        metricsService.record(reqId, provider, request.latency, request.tokens);

        return ResponseEntity.ok(Map.of(
            "status", "recorded",
            "request_id", reqId,
            "provider", provider
        ));
    }

    @GetMapping("/metrics")
    public ResponseEntity<Object> getMetrics() {
        log.info("Retrieving aggregated metrics");
        return ResponseEntity.ok(metricsService.getAggregated());
    }

    static class MetricsRequest {
        public String requestId;
        public String provider;
        public long latency;
        public int tokens;
    }
}
