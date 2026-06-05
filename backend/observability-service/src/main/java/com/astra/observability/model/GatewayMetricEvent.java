package com.astra.observability.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayMetricEvent {
    @Builder.Default
    private String timestamp = Instant.now().toString();
    private String requestId;
    private String provider;
    private String model;
    private long latencyMs;
    private int inputTokens;
    private int outputTokens;
    private int totalTokens;
    private double costUsd;
    @Builder.Default
    private String status = "success";
    @Builder.Default
    private String tenantId = "";
}
