package com.astra.observability.service;

import com.astra.observability.model.GatewayMetricEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsService {

    private final MeterRegistry meterRegistry;
    private final ClickHousePublisher clickHousePublisher;

    private final ConcurrentHashMap<String, ProviderStats> providerStats = new ConcurrentHashMap<>();
    private static final int MAX_RECENT = 1000;
    private final List<MetricEntry> recentMetrics = Collections.synchronizedList(new ArrayList<>());

    public void record(String requestId, String provider, String model,
                       long latencyMs, int inputTokens, int outputTokens,
                       double costUsd, String status, String tenantId) {

        int totalTokens = inputTokens + outputTokens;

        // Micrometer counters (Prometheus scrape)
        meterRegistry.counter("observability.tokens.total", "provider", provider, "model", model)
            .increment(totalTokens);
        meterRegistry.timer("observability.latency", "provider", provider)
            .record(latencyMs, TimeUnit.MILLISECONDS);
        meterRegistry.counter("observability.requests.total", "provider", provider, "status", status)
            .increment();

        // In-memory aggregation
        ProviderStats stats = providerStats.computeIfAbsent(provider, p -> new ProviderStats());
        stats.requestCount.incrementAndGet();
        stats.totalLatencyMs.addAndGet(latencyMs);
        stats.totalTokens.addAndGet(totalTokens);

        synchronized (recentMetrics) {
            recentMetrics.add(new MetricEntry(requestId, provider, model, latencyMs, totalTokens, System.currentTimeMillis()));
            if (recentMetrics.size() > MAX_RECENT) recentMetrics.remove(0);
        }

        // Publish to ClickHouse (non-blocking enqueue)
        clickHousePublisher.enqueue(GatewayMetricEvent.builder()
            .requestId(requestId)
            .provider(provider)
            .model(model != null ? model : "")
            .latencyMs(latencyMs)
            .inputTokens(inputTokens)
            .outputTokens(outputTokens)
            .totalTokens(totalTokens)
            .costUsd(costUsd)
            .status(status != null ? status : "success")
            .tenantId(tenantId != null ? tenantId : "")
            .build());

        log.debug("Recorded: provider={} model={} latency={}ms tokens={}", provider, model, latencyMs, totalTokens);
    }

    /** Backward-compatible overload used by existing callers. */
    public void record(String requestId, String provider, long latencyMs, int tokens) {
        record(requestId, provider, null, latencyMs, tokens, 0, 0.0, "success", null);
    }

    public Map<String, Object> getAggregated() {
        Map<String, Object> byProvider = new LinkedHashMap<>();
        providerStats.forEach((provider, stats) -> {
            long count = stats.requestCount.get();
            byProvider.put(provider, Map.of(
                "request_count", count,
                "total_tokens", stats.totalTokens.get(),
                "avg_latency_ms", count > 0 ? stats.totalLatencyMs.get() / count : 0
            ));
        });
        int recentCount;
        synchronized (recentMetrics) { recentCount = recentMetrics.size(); }
        return Map.of(
            "by_provider", byProvider,
            "total_recorded", recentCount,
            "providers_seen", providerStats.keySet()
        );
    }

    static class ProviderStats {
        final AtomicLong requestCount  = new AtomicLong(0);
        final AtomicLong totalLatencyMs = new AtomicLong(0);
        final AtomicLong totalTokens   = new AtomicLong(0);
    }

    record MetricEntry(String requestId, String provider, String model,
                       long latencyMs, int tokens, long timestamp) {}
}
