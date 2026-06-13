package com.astra.gateway.controller;

import com.astra.gateway.client.ObservabilityClient;
import com.astra.gateway.client.RoutingClient;
import com.astra.gateway.interceptor.AuthInterceptor;
import com.astra.gateway.mcp.service.McpToolOrchestrator;
import com.astra.gateway.service.ProviderService;
import com.astra.gateway.service.RequestLogService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/v1")
public class GatewayController {

    @Autowired private MeterRegistry        meterRegistry;
    @Autowired private ProviderService      providerService;
    @Autowired private RequestLogService    requestLogService;
    @Autowired private ObservabilityClient  observabilityClient;
    @Autowired private RoutingClient        routingClient;
    @Autowired private McpToolOrchestrator  mcpToolOrchestrator;
    @Autowired private ObjectMapper         objectMapper;

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(new HealthResponse("healthy", "gateway-service"));
    }

    @PostMapping("/chat/completions")
    public ResponseEntity<Object> chatCompletion(
            @RequestBody JsonNode request,
            @RequestHeader(value = "X-Routing-Strategy", required = false) String strategy,
            HttpServletRequest httpRequest) {

        String model    = request.has("model") ? request.get("model").asText() : "claude-sonnet-4-6";
        String tenantId = (String) httpRequest.getAttribute(AuthInterceptor.TENANT_ID_ATTR);

        log.info("Chat completion: model={} tenant={}", model, tenantId);
        meterRegistry.counter("gateway.requests.total", "model", model, "tenant", tenantId != null ? tenantId : "unknown").increment();
        Timer.Sample sample = Timer.start(meterRegistry);

        if (request.has("stream") && request.get("stream").asBoolean()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "For streaming, use POST /v1/chat/completions?stream=true"));
        }

        String provider = routingClient.decide(model, strategy);
        log.info("Resolved provider: {} for model: {} (strategy: {})", provider, model, strategy);

        long startMs = System.currentTimeMillis();
        try {
            JsonNode response = mcpToolOrchestrator.completeWithTools(request, provider);
            int latency      = (int) (System.currentTimeMillis() - startMs);
            int inputTokens  = response.path("usage").path("prompt_tokens").asInt(0);
            int outputTokens = response.path("usage").path("completion_tokens").asInt(0);

            sample.stop(meterRegistry.timer("gateway.request.duration", "model", model));
            meterRegistry.counter("gateway.requests.success", "model", model).increment();
            requestLogService.log(model, model, "success", inputTokens, outputTokens, latency, null);
            observabilityClient.record(provider, model, latency, inputTokens, outputTokens, 0.0, "success", tenantId);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            int latency = (int) (System.currentTimeMillis() - startMs);
            log.error("Chat completion failed for model {}: {}", model, e.getMessage());
            meterRegistry.counter("gateway.requests.error", "model", model).increment();
            requestLogService.log(model, model, "failed", 0, 0, latency, e.getMessage());
            observabilityClient.record(provider, model, latency, 0, 0, 0.0, "failed", tenantId);
            return ResponseEntity.status(500)
                .body(Map.of("error", "Provider call failed", "message", e.getMessage()));
        }
    }

    private static final Set<String> TOKEN_STREAMING_PROVIDERS = Set.of("openai", "google");

    @PostMapping(value = "/chat/completions", params = "stream=true")
    public SseEmitter streamChatCompletion(
            @RequestBody JsonNode request,
            @RequestHeader(value = "X-Routing-Strategy", required = false) String strategy,
            HttpServletRequest httpRequest) {

        String model    = request.has("model") ? request.get("model").asText() : "claude-sonnet-4-6";
        String tenantId = (String) httpRequest.getAttribute(AuthInterceptor.TENANT_ID_ATTR);
        String provider = routingClient.decide(model, strategy);
        SseEmitter emitter = new SseEmitter(120_000L);

        meterRegistry.counter("gateway.stream.requests.total", "model", model).increment();

        if (TOKEN_STREAMING_PROVIDERS.contains(provider) && !mcpToolOrchestrator.hasTools()) {
            streamFromProvider(request, provider, model, tenantId, emitter);
        } else {
            streamSingleShot(request, provider, model, tenantId, emitter);
        }

        return emitter;
    }

    /** True incremental SSE streaming: passes provider chunks straight through to the client. */
    private void streamFromProvider(JsonNode request, String provider, String model, String tenantId, SseEmitter emitter) {
        long startMs = System.currentTimeMillis();
        log.info("Stream (token): model={} provider={} tenant={}", model, provider, tenantId);

        providerService.streamChatCompletion(request, provider)
            .filter(chunk -> !"[DONE]".equals(chunk))
            .subscribe(
                chunk -> {
                    try {
                        emitter.send(SseEmitter.event().data(chunk));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                error -> {
                    int latency = (int) (System.currentTimeMillis() - startMs);
                    log.error("Stream failed for model {}: {}", model, error.getMessage());
                    meterRegistry.counter("gateway.stream.requests.error", "model", model).increment();
                    observabilityClient.record(provider, model, latency, 0, 0, 0.0, "failed", tenantId);
                    emitter.completeWithError(error);
                },
                () -> {
                    int latency = (int) (System.currentTimeMillis() - startMs);
                    try {
                        emitter.send(SseEmitter.event().data("[DONE]"));
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                        return;
                    }
                    meterRegistry.counter("gateway.stream.requests.success", "model", model).increment();
                    observabilityClient.record(provider, model, latency, 0, 0, 0.0, "success", tenantId);
                }
            );
    }

    /** Fallback for Anthropic and tool-augmented requests: one SSE event with the full response. */
    private void streamSingleShot(JsonNode request, String provider, String model, String tenantId, SseEmitter emitter) {
        new Thread(() -> {
            long startMs = System.currentTimeMillis();
            try {
                log.info("Stream (single-shot): model={} provider={} tenant={}", model, provider, tenantId);
                JsonNode response    = mcpToolOrchestrator.completeWithTools(request, provider);
                int latency          = (int) (System.currentTimeMillis() - startMs);
                int inputTokens      = response.path("usage").path("prompt_tokens").asInt(0);
                int outputTokens     = response.path("usage").path("completion_tokens").asInt(0);

                emitter.send(SseEmitter.event().id("1").name("message")
                    .data(objectMapper.writeValueAsString(response)));
                emitter.send(SseEmitter.event().name("message").data("[DONE]"));
                emitter.complete();

                meterRegistry.counter("gateway.stream.requests.success", "model", model).increment();
                observabilityClient.record(provider, model, latency, inputTokens, outputTokens, 0.0, "success", tenantId);
            } catch (Exception e) {
                int latency = (int) (System.currentTimeMillis() - startMs);
                log.error("Stream failed for model {}: {}", model, e.getMessage());
                meterRegistry.counter("gateway.stream.requests.error", "model", model).increment();
                observabilityClient.record(provider, model, latency, 0, 0, 0.0, "failed", tenantId);
                emitter.completeWithError(e);
            }
        }).start();
    }

    @GetMapping("/models")
    public ResponseEntity<Object> listModels() {
        return ResponseEntity.ok(Map.of("object", "list", "data", providerService.listModels()));
    }

    static class HealthResponse {
        public final String status;
        public final String service;
        HealthResponse(String status, String service) { this.status = status; this.service = service; }
        public String getStatus()  { return status; }
        public String getService() { return service; }
    }
}
