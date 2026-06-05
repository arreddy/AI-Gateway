package com.astra.auth.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "external_id", nullable = false, unique = true)
    private UUID externalId;

    /** SHA-256 hash of the raw key — never store the plain key. */
    @Column(name = "key_hash", nullable = false, unique = true)
    private String keyHash;

    /** Last 8 chars of the raw key for display purposes. */
    @Column(name = "key_preview", length = 20)
    private String keyPreview;

    @Column(length = 255)
    private String name;

    /** Maps to PostgreSQL api_key_status enum. */
    @Column(columnDefinition = "varchar(50)")
    private String status = "active";

    /** Comma-separated permission strings e.g. "completions:create,models:list". */
    @Column(columnDefinition = "text")
    private String permissions = "completions:create,models:list";

    @Column(name = "total_requests")
    private Long totalRequests = 0L;

    @Column(name = "total_tokens")
    private Long totalTokens = 0L;

    @Column(name = "rate_limit_rpm")
    private Integer rateLimitRpm;

    @Column(name = "rate_limit_tpm")
    private Integer rateLimitTpm;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @PrePersist
    void prePersist() {
        if (externalId == null) externalId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
