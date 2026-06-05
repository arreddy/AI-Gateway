package com.astra.routing.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutingServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        MeterRegistry registry = new SimpleMeterRegistry();
        routingService = new RoutingService();
        ReflectionTestUtils.setField(routingService, "meterRegistry", registry);
        ReflectionTestUtils.setField(routingService, "redisTemplate", redisTemplate);
        // lenient: getProviderMetrics tests don't touch Redis
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ── resolveProvider via decideRouting ──────────────────────────────────────

    @Test
    void decideRouting_claudeModel_routesToAnthropic() {
        Map<String, Object> result = routingService.decideRouting("claude-sonnet-4-6", null);
        assertThat(result.get("selected_provider")).isEqualTo("anthropic");
    }

    @Test
    void decideRouting_gptModel_routesToOpenAI() {
        Map<String, Object> result = routingService.decideRouting("gpt-4o", null);
        assertThat(result.get("selected_provider")).isEqualTo("openai");
    }

    @Test
    void decideRouting_o1Model_routesToOpenAI() {
        Map<String, Object> result = routingService.decideRouting("o1-preview", null);
        assertThat(result.get("selected_provider")).isEqualTo("openai");
    }

    @Test
    void decideRouting_geminiModel_routesToGoogle() {
        Map<String, Object> result = routingService.decideRouting("gemini-2.5-flash", null);
        assertThat(result.get("selected_provider")).isEqualTo("google");
    }

    @Test
    void decideRouting_commandModel_routesToCohere() {
        Map<String, Object> result = routingService.decideRouting("command-r", null);
        assertThat(result.get("selected_provider")).isEqualTo("cohere");
    }

    @Test
    void decideRouting_unknownModel_defaultsToAnthropic() {
        Map<String, Object> result = routingService.decideRouting("unknown-model-xyz", null);
        assertThat(result.get("selected_provider")).isEqualTo("anthropic");
    }

    @Test
    void decideRouting_nullModel_defaultsToAnthropic() {
        Map<String, Object> result = routingService.decideRouting(null, null);
        assertThat(result.get("selected_provider")).isEqualTo("anthropic");
    }

    // ── response structure ────────────────────────────────────────────────────

    @Test
    void decideRouting_responseHasRequiredFields() {
        Map<String, Object> result = routingService.decideRouting("gpt-4o", "latency");
        assertThat(result).containsKeys("selected_provider", "model", "strategy", "fallback_chain", "constraints", "timestamp");
    }

    @Test
    void decideRouting_nullStrategy_defaultsToLatency() {
        Map<String, Object> result = routingService.decideRouting("gpt-4o", null);
        assertThat(result.get("strategy")).isEqualTo("latency");
    }

    @Test
    void decideRouting_fallbackChain_doesNotRepeatPrimary() {
        Map<String, Object> result = routingService.decideRouting("claude-sonnet-4-6", "latency");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chain = (List<Map<String, Object>>) result.get("fallback_chain");
        long anthropicCount = chain.stream()
            .filter(e -> "anthropic".equals(e.get("provider")))
            .count();
        assertThat(anthropicCount).isEqualTo(1);
    }

    @Test
    void decideRouting_fallbackChain_primaryHasPriorityOne() {
        Map<String, Object> result = routingService.decideRouting("claude-sonnet-4-6", "latency");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chain = (List<Map<String, Object>>) result.get("fallback_chain");
        Map<String, Object> first = chain.get(0);
        assertThat(first.get("provider")).isEqualTo("anthropic");
        assertThat(first.get("priority")).isEqualTo(1);
    }

    // ── strategies ────────────────────────────────────────────────────────────

    @Test
    void decideRouting_costStrategy_returnsDecision() {
        Map<String, Object> result = routingService.decideRouting("gpt-4o", "cost");
        assertThat(result.get("strategy")).isEqualTo("cost");
        assertThat(result.get("selected_provider")).isNotNull();
    }

    @Test
    void decideRouting_qualityStrategy_returnsDecision() {
        Map<String, Object> result = routingService.decideRouting("gpt-4o", "quality");
        assertThat(result.get("strategy")).isEqualTo("quality");
    }

    @Test
    void decideRouting_latencyStrategy_returnsDecision() {
        Map<String, Object> result = routingService.decideRouting("gpt-4o", "latency");
        assertThat(result.get("strategy")).isEqualTo("latency");
    }

    // ── Redis caching ─────────────────────────────────────────────────────────

    @Test
    void decideRouting_redisFails_doesNotThrow() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis down"));
        Map<String, Object> result = routingService.decideRouting("gpt-4o", "latency");
        assertThat(result.get("selected_provider")).isEqualTo("openai");
    }

    // ── provider metrics ──────────────────────────────────────────────────────

    @Test
    void getProviderMetrics_containsAllProviders() {
        Map<String, Object> metrics = routingService.getProviderMetrics();
        assertThat(metrics).containsKeys("anthropic", "openai", "google", "cohere");
    }

    @Test
    void getProviderMetrics_providerHasRequiredFields() {
        Map<String, Object> metrics = routingService.getProviderMetrics();
        @SuppressWarnings("unchecked")
        Map<String, Object> anthropic = (Map<String, Object>) metrics.get("anthropic");
        assertThat(anthropic).containsKeys("cost_per_1k_tokens", "avg_latency_ms", "quality_score", "request_count", "error_rate", "available");
    }

    @Test
    void getProviderMetrics_initialRequestCount_isZero() {
        Map<String, Object> metrics = routingService.getProviderMetrics();
        @SuppressWarnings("unchecked")
        Map<String, Object> anthropic = (Map<String, Object>) metrics.get("anthropic");
        assertThat(anthropic.get("request_count")).isEqualTo(0L);
    }

    @Test
    void getProviderMetrics_google_hasCorrectCostPerToken() {
        Map<String, Object> metrics = routingService.getProviderMetrics();
        @SuppressWarnings("unchecked")
        Map<String, Object> google = (Map<String, Object>) metrics.get("google");
        assertThat((Double) google.get("cost_per_1k_tokens")).isLessThan(0.01);
    }
}
