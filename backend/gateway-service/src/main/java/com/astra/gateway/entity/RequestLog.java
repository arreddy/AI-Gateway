package com.astra.gateway.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;


@Data
@NoArgsConstructor
@Entity
@Table(name = "request_logs")
public class RequestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "request_id", nullable = false, unique = true)
    private UUID requestId;

    @Column(name = "model_requested")
    private String modelRequested;

    @Column(name = "model_used")
    private String modelUsed;

    @Column(name = "request_type", length = 50)
    private String requestType = "chat";

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "input_cost", precision = 15, scale = 8)
    private BigDecimal inputCost;

    @Column(name = "output_cost", precision = 15, scale = 8)
    private BigDecimal outputCost;

    @Column(name = "total_cost", precision = 15, scale = 8)
    private BigDecimal totalCost;

    /** Maps to PostgreSQL request_status enum. */
    @Column(columnDefinition = "varchar(50)")
    private String status = "success";

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "pii_detected")
    private Boolean piiDetected = false;

    @Column(name = "injection_detected")
    private Boolean injectionDetected = false;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (requestId == null) requestId = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
