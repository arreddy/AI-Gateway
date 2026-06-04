package com.astra.routing.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class RoutingService {

    private static final Map<String, ProviderMeta> CATALOG = Map.of(
        "anthropic", new ProviderMeta("anthropic", 0.015, 100.0, 0.95),
        "openai",    new ProviderMeta("openai",    0.010, 120.0, 0.93),
        "cohere",    new ProviderMeta("cohere",    0.002, 180.0, 0.85)
    );

    private final ConcurrentHashMap<String, RuntimeStats> runtimeStats = new ConcurrentHashMap<>();

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private StringRedisTemplate redisTemplate;

    public Map<String, Object> decideRouting(String model, String strategy) {
        String provider = resolveProvider(model);
        String effectiveStrategy = strategy != null ? strategy : "latency";
        List<Map<String, Object>> fallbackChain = buildFallbackChain(provider, effectiveStrategy);

        meterRegistry.counter("routing.decisions.total", "strategy", effectiveStrategy).increment();
        log.info("Routing decision: model={}, strategy={}, provider={}", model, effectiveStrategy, provider);

        // Cache routing decision in Redis (60s TTL)
        try {
            redisTemplate.opsForValue().set(
                "routing:" + model + ":" + effectiveStrategy,
                provider,
                Duration.ofSeconds(60)
            );
        } catch (Exception e) {
            log.warn("Failed to cache routing decision: {}", e.getMessage());
        }

        return Map.of(
            "selected_provider", provider,
            "model", model != null ? model : "claude-sonnet-4-6",
            "strategy", effectiveStrategy,
            "fallback_chain", fallbackChain,
            "constraints", Map.of("max_tokens", 4096, "timeout_ms", 30000),
            "timestamp", System.currentTimeMillis()
        );
    }

    public Map<String, Object> getProviderMetrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        CATALOG.forEach((provider, meta) -> {
            RuntimeStats stats = runtimeStats.getOrDefault(provider, new RuntimeStats());
            result.put(provider, Map.of(
                "cost_per_1k_tokens", meta.costPer1kTokens,
                "avg_latency_ms", stats.requestCount > 0 ? stats.totalLatencyMs / stats.requestCount : meta.baseLatencyMs,
                "quality_score", meta.qualityScore,
                "request_count", stats.requestCount,
                "error_rate", stats.requestCount > 0 ? (double) stats.errorCount / stats.requestCount : 0.0,
                "available", true
            ));
        });
        return result;
    }

    public void recordProviderCall(String provider, long latencyMs, boolean success) {
        runtimeStats.compute(provider, (k, existing) -> {
            RuntimeStats s = existing != null ? existing : new RuntimeStats();
            s.requestCount++;
            s.totalLatencyMs += latencyMs;
            if (!success) s.errorCount++;
            return s;
        });
    }

    private String resolveProvider(String model) {
        if (model == null) return "anthropic";
        if (model.startsWith("claude")) return "anthropic";
        if (model.startsWith("gpt") || model.startsWith("o1") || model.startsWith("o3")) return "openai";
        if (model.startsWith("command")) return "cohere";
        return "anthropic";
    }

    private List<Map<String, Object>> buildFallbackChain(String primary, String strategy) {
        List<String> ranked = rankProviders(strategy);
        List<Map<String, Object>> chain = new ArrayList<>();
        chain.add(Map.of("provider", primary, "priority", 1));
        int priority = 2;
        for (String p : ranked) {
            if (!p.equals(primary)) {
                chain.add(Map.of("provider", p, "priority", priority++));
            }
        }
        return chain;
    }

    private List<String> rankProviders(String strategy) {
        List<String> providers = new ArrayList<>(CATALOG.keySet());
        switch (strategy.toLowerCase()) {
            case "cost" -> providers.sort(Comparator.comparingDouble(p -> CATALOG.get(p).costPer1kTokens));
            case "quality" -> providers.sort(Comparator.comparingDouble((String p) -> CATALOG.get(p).qualityScore).reversed());
            default -> providers.sort(Comparator.comparingDouble(p -> {
                RuntimeStats s = runtimeStats.get(p);
                return s != null && s.requestCount > 0
                    ? (double) s.totalLatencyMs / s.requestCount
                    : CATALOG.get(p).baseLatencyMs;
            }));
        }
        return providers;
    }

    record ProviderMeta(String name, double costPer1kTokens, double baseLatencyMs, double qualityScore) {}

    static class RuntimeStats {
        long requestCount = 0;
        long errorCount = 0;
        long totalLatencyMs = 0;
    }
}
