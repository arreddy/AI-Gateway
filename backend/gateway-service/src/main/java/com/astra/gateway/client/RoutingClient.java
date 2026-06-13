package com.astra.gateway.client;

import com.astra.gateway.config.ServicesProperties;
import com.astra.gateway.service.ProviderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Asks the routing-engine which provider to use for a given model + strategy.
 * Falls back to local resolution if the routing-engine is unreachable or slow,
 * so gateway availability is never coupled to routing-engine availability.
 */
@Slf4j
@Component
public class RoutingClient {

    private final RestClient http;
    private final ServicesProperties services;
    private final ProviderService providerService;

    public RoutingClient(ServicesProperties services, ProviderService providerService, RestClient.Builder restClientBuilder) {
        this.services        = services;
        this.providerService = providerService;

        // Short-timeout factory — routing must never stall a client request
        int ms = services.getRouting().getTimeoutMs();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(ms);
        factory.setReadTimeout(ms);
        this.http = restClientBuilder.requestFactory(factory).build();
    }

    /**
     * Returns the provider to use (e.g. "anthropic", "openai").
     * strategy: "latency" | "cost" | "quality" — defaults to "latency".
     */
    @SuppressWarnings("unchecked")
    public String decide(String model, String strategy) {
        String effectiveStrategy = strategy != null ? strategy : "latency";
        try {
            String url = services.getRouting().getUrl() + "/v1/routing/decide";
            Map<String, String> body = Map.of("model", model, "strategy", effectiveStrategy);

            Map<String, Object> response = http.post().uri(url).body(body).retrieve().body(Map.class);
            if (response != null && response.containsKey("selected_provider")) {
                String provider = response.get("selected_provider").toString();
                log.debug("Routing decision: model={} strategy={} → provider={}", model, effectiveStrategy, provider);
                return provider;
            }
        } catch (Exception e) {
            log.warn("Routing-engine unreachable ({}ms timeout), falling back to local resolution: {}",
                services.getRouting().getTimeoutMs(), e.getMessage());
        }
        return providerService.resolveProvider(model);
    }

}
