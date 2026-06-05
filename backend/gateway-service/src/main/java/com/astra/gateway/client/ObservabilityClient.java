package com.astra.gateway.client;

import com.astra.gateway.config.ServicesProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fire-and-forget client that pushes per-request metrics to the
 * observability-service. @Async means the caller (GatewayController)
 * is never blocked — this call never adds latency to the API response path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ObservabilityClient {

    private final RestTemplate restTemplate;
    private final ServicesProperties servicesProperties;

    @Async
    public void record(String provider, String model, long latencyMs,
                       int inputTokens, int outputTokens, double costUsd,
                       String status, String tenantId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("provider",     provider);
            payload.put("model",        model);
            payload.put("latencyMs",    latencyMs);
            payload.put("inputTokens",  inputTokens);
            payload.put("outputTokens", outputTokens);
            payload.put("costUsd",      costUsd);
            payload.put("status",       status);
            payload.put("tenantId",     tenantId != null ? tenantId : "");

            String url = servicesProperties.getObservability().getUrl()
                         + "/v1/observability/metrics/record";
            restTemplate.postForEntity(url, payload, Void.class);
        } catch (Exception e) {
            log.warn("Failed to publish metrics to observability-service: {}", e.getMessage());
        }
    }
}
