package com.astra.observability.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class MetricsService {

    @Autowired
    private MeterRegistry meterRegistry;

    private final ConcurrentHashMap<String, ProviderStats> providerStats = new ConcurrentHashMap<>();
    private final List<MetricEntry> recentMetrics = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_RECENT = 1000;

    public void record(String requestId, String provider, long latencyMs, int tokens) {
        meterRegistry.counter("observability.tokens.total", "provider", provider).increment(tokens);
        meterRegistry.timer("observability.latency", "provider", provider)
            .record(latencyMs, TimeUnit.MILLISECONDS);

        ProviderStats stats = providerStats.computeIfAbsent(provider, p -> new ProviderStats());
        stats.requestCount.incrementAndGet();
        stats.totalLatencyMs.addAndGet(latencyMs);
        stats.totalTokens.addAndGet(tokens);

        synchronized (recentMetrics) {
            recentMetrics.add(new MetricEntry(requestId, provider, latencyMs, tokens, System.currentTimeMillis()));
            if (recentMetrics.size() > MAX_RECENT) {
                recentMetrics.remove(0);
            }
        }

        log.debug("Recorded: provider={}, latency={}ms, tokens={}", provider, latencyMs, tokens);
    }

    public Map<String, Object> getAggregated() {
        Map<String, Object> byProvider = new LinkedHashMap<>();
        providerStats.forEach((provider, stats) -> {
            long count = stats.requestCount.get();
            long totalMs = stats.totalLatencyMs.get();
            byProvider.put(provider, Map.of(
                "request_count", count,
                "total_tokens", stats.totalTokens.get(),
                "avg_latency_ms", count > 0 ? totalMs / count : 0,
                "total_latency_ms", totalMs
            ));
        });

        int recentCount;
        synchronized (recentMetrics) {
            recentCount = recentMetrics.size();
        }

        return Map.of(
            "by_provider", byProvider,
            "total_recorded", recentCount,
            "providers_seen", providerStats.keySet()
        );
    }

    static class ProviderStats {
        final AtomicLong requestCount = new AtomicLong(0);
        final AtomicLong totalLatencyMs = new AtomicLong(0);
        final AtomicLong totalTokens = new AtomicLong(0);
    }

    record MetricEntry(String requestId, String provider, long latencyMs, int tokens, long timestamp) {}
}
