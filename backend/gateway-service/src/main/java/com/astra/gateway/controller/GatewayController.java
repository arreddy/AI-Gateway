package com.astra.gateway.controller;

import com.astra.gateway.service.ProviderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/v1")
public class GatewayController {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ProviderService providerService;

    @Autowired
    private ObjectMapper objectMapper;

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(new HealthResponse("healthy", "gateway-service"));
    }

    @PostMapping("/chat/completions")
    public ResponseEntity<Object> chatCompletion(@RequestBody JsonNode request) {
        String model = request.has("model") ? request.get("model").asText() : "claude-sonnet-4-6";
        log.info("Chat completion request for model: {}", model);

        meterRegistry.counter("gateway.requests.total", "model", model).increment();
        Timer.Sample sample = Timer.start(meterRegistry);

        // Stream requests must use the stream endpoint
        if (request.has("stream") && request.get("stream").asBoolean()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "For streaming, use POST /v1/chat/completions?stream=true"));
        }

        try {
            JsonNode response = providerService.chatCompletion(request);
            sample.stop(meterRegistry.timer("gateway.request.duration", "model", model));
            meterRegistry.counter("gateway.requests.success", "model", model).increment();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Chat completion failed for model {}: {}", model, e.getMessage());
            meterRegistry.counter("gateway.requests.error", "model", model).increment();
            return ResponseEntity.status(500)
                .body(Map.of("error", "Provider call failed", "message", e.getMessage()));
        }
    }

    @PostMapping(value = "/chat/completions", params = "stream=true")
    public SseEmitter streamChatCompletion(@RequestBody JsonNode request) {
        String model = request.has("model") ? request.get("model").asText() : "claude-sonnet-4-6";
        SseEmitter emitter = new SseEmitter(120_000L);

        meterRegistry.counter("gateway.stream.requests.total", "model", model).increment();

        new Thread(() -> {
            try {
                log.info("Streaming chat completion for model: {}", model);
                JsonNode response = providerService.chatCompletion(request);

                emitter.send(SseEmitter.event()
                    .id("1")
                    .name("message")
                    .data(objectMapper.writeValueAsString(response))
                );
                emitter.send(SseEmitter.event()
                    .name("message")
                    .data("[DONE]")
                );
                emitter.complete();
                meterRegistry.counter("gateway.stream.requests.success", "model", model).increment();
            } catch (Exception e) {
                log.error("Stream failed for model {}: {}", model, e.getMessage());
                meterRegistry.counter("gateway.stream.requests.error", "model", model).increment();
                emitter.completeWithError(e);
            }
        }).start();

        return emitter;
    }

    @GetMapping("/models")
    public ResponseEntity<Object> listModels() {
        log.info("Listing available models");
        List<Map<String, Object>> models = providerService.listModels();
        return ResponseEntity.ok(Map.of("object", "list", "data", models));
    }

    static class HealthResponse {
        public String status;
        public String service;

        public HealthResponse(String status, String service) {
            this.status = status;
            this.service = service;
        }

        public String getStatus() { return status; }
        public String getService() { return service; }
    }
}
