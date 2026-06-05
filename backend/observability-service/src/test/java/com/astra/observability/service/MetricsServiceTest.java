package com.astra.observability.service;

import com.astra.observability.model.GatewayMetricEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetricsServiceTest {

    @Mock
    private ClickHousePublisher clickHousePublisher;

    private MetricsService metricsService;

    @BeforeEach
    void setUp() {
        metricsService = new MetricsService(new SimpleMeterRegistry(), clickHousePublisher);
    }

    @Test
    void record_incrementsProviderRequestCount() {
        metricsService.record("req-1", "anthropic", "claude-sonnet-4-6", 150, 100, 50, 0.001, "success", "tenant-1");

        Map<String, Object> aggregated = metricsService.getAggregated();
        @SuppressWarnings("unchecked")
        Map<String, Object> byProvider = (Map<String, Object>) aggregated.get("by_provider");
        @SuppressWarnings("unchecked")
        Map<String, Object> anthropic = (Map<String, Object>) byProvider.get("anthropic");

        assertThat(anthropic.get("request_count")).isEqualTo(1L);
    }

    @Test
    void record_accumulatesTokens() {
        metricsService.record("req-1", "openai", "gpt-4o", 200, 300, 100, 0.005, "success", "t1");
        metricsService.record("req-2", "openai", "gpt-4o", 100, 200, 50,  0.003, "success", "t1");

        Map<String, Object> aggregated = metricsService.getAggregated();
        @SuppressWarnings("unchecked")
        Map<String, Object> byProvider = (Map<String, Object>) aggregated.get("by_provider");
        @SuppressWarnings("unchecked")
        Map<String, Object> openai = (Map<String, Object>) byProvider.get("openai");

        assertThat(openai.get("total_tokens")).isEqualTo(650L); // (300+100) + (200+50)
    }

    @Test
    void record_calculatesAverageLatency() {
        metricsService.record("req-1", "google", "gemini-2.5-flash", 100, 50, 50, 0.001, "success", "t1");
        metricsService.record("req-2", "google", "gemini-2.5-flash", 300, 50, 50, 0.001, "success", "t1");

        Map<String, Object> aggregated = metricsService.getAggregated();
        @SuppressWarnings("unchecked")
        Map<String, Object> byProvider = (Map<String, Object>) aggregated.get("by_provider");
        @SuppressWarnings("unchecked")
        Map<String, Object> google = (Map<String, Object>) byProvider.get("google");

        assertThat(google.get("avg_latency_ms")).isEqualTo(200L);
    }

    @Test
    void record_enqueuesToClickHouse() {
        metricsService.record("req-1", "anthropic", "claude-haiku", 100, 10, 20, 0.001, "success", "t1");

        ArgumentCaptor<GatewayMetricEvent> captor = ArgumentCaptor.forClass(GatewayMetricEvent.class);
        verify(clickHousePublisher).enqueue(captor.capture());

        GatewayMetricEvent event = captor.getValue();
        assertThat(event.getRequestId()).isEqualTo("req-1");
        assertThat(event.getProvider()).isEqualTo("anthropic");
        assertThat(event.getModel()).isEqualTo("claude-haiku");
        assertThat(event.getLatencyMs()).isEqualTo(100L);
        assertThat(event.getTotalTokens()).isEqualTo(30);
    }

    @Test
    void record_nullModel_defaultsToEmpty() {
        metricsService.record("req-1", "anthropic", null, 100, 10, 20, 0.001, "success", "t1");

        ArgumentCaptor<GatewayMetricEvent> captor = ArgumentCaptor.forClass(GatewayMetricEvent.class);
        verify(clickHousePublisher).enqueue(captor.capture());
        assertThat(captor.getValue().getModel()).isEqualTo("");
    }

    @Test
    void record_nullStatus_defaultsToSuccess() {
        metricsService.record("req-1", "openai", "gpt-4o", 100, 10, 20, 0.001, null, "t1");

        ArgumentCaptor<GatewayMetricEvent> captor = ArgumentCaptor.forClass(GatewayMetricEvent.class);
        verify(clickHousePublisher).enqueue(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("success");
    }

    @Test
    void record_nullTenantId_defaultsToEmpty() {
        metricsService.record("req-1", "anthropic", "claude-sonnet-4-6", 100, 10, 20, 0.001, "success", null);

        ArgumentCaptor<GatewayMetricEvent> captor = ArgumentCaptor.forClass(GatewayMetricEvent.class);
        verify(clickHousePublisher).enqueue(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("");
    }

    @Test
    void record_multipleProviders_trackedSeparately() {
        metricsService.record("r1", "anthropic", "claude-sonnet-4-6", 100, 100, 50, 0.001, "success", "t1");
        metricsService.record("r2", "openai",    "gpt-4o",            200, 200, 50, 0.002, "success", "t1");

        Map<String, Object> aggregated = metricsService.getAggregated();
        @SuppressWarnings("unchecked")
        Map<String, Object> byProvider = (Map<String, Object>) aggregated.get("by_provider");

        assertThat(byProvider).containsKeys("anthropic", "openai");
    }

    @Test
    void getAggregated_totalRecorded_reflectsCount() {
        metricsService.record("r1", "anthropic", "claude-sonnet-4-6", 100, 10, 10, 0.001, "success", "t1");
        metricsService.record("r2", "anthropic", "claude-sonnet-4-6", 200, 20, 20, 0.002, "success", "t1");

        Map<String, Object> result = metricsService.getAggregated();
        assertThat(result.get("total_recorded")).isEqualTo(2);
    }

    @Test
    void getAggregated_empty_returnsEmptyByProvider() {
        Map<String, Object> result = metricsService.getAggregated();
        @SuppressWarnings("unchecked")
        Map<String, Object> byProvider = (Map<String, Object>) result.get("by_provider");
        assertThat(byProvider).isEmpty();
    }

    @Test
    void record_maxRecentLimit_doesNotGrowUnbounded() {
        for (int i = 0; i < 1100; i++) {
            metricsService.record("req-" + i, "anthropic", "claude-sonnet-4-6", 100, 10, 10, 0.001, "success", "t1");
        }

        Map<String, Object> result = metricsService.getAggregated();
        assertThat((Integer) result.get("total_recorded")).isLessThanOrEqualTo(1000);
    }
}
