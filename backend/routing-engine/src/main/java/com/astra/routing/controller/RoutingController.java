package com.astra.routing.controller;

import com.astra.routing.service.RoutingService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/v1/routing")
public class RoutingController {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private RoutingService routingService;

    @GetMapping("/health")
    public ResponseEntity<Object> health() {
        return ResponseEntity.ok(Map.of("status", "healthy", "service", "routing-engine"));
    }

    @PostMapping("/decide")
    public ResponseEntity<Object> decideRouting(@RequestBody RoutingRequest request) {
        log.info("Deciding routing for model: {}, strategy: {}", request.model, request.strategy);
        meterRegistry.counter("routing.decisions.total").increment();
        Map<String, Object> decision = routingService.decideRouting(request.model, request.strategy);
        return ResponseEntity.ok(decision);
    }

    @GetMapping("/metrics")
    public ResponseEntity<Object> getProviderMetrics() {
        log.info("Getting provider metrics");
        return ResponseEntity.ok(routingService.getProviderMetrics());
    }

    static class RoutingRequest {
        public String model;
        public String strategy; // cost, latency, quality
        public String getModel() { return model; }
        public String getStrategy() { return strategy; }
    }
}
