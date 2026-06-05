package com.astra.gateway.repository;

import com.astra.gateway.entity.RequestLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface RequestLogRepository extends JpaRepository<RequestLog, Long> {
    Page<RequestLog> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);

    @Query("SELECT r FROM RequestLog r WHERE r.tenantId = :tenantId AND r.createdAt >= :since")
    List<RequestLog> findRecentByTenant(Long tenantId, OffsetDateTime since);

    @Query("SELECT SUM(r.totalTokens) FROM RequestLog r WHERE r.tenantId = :tenantId AND r.createdAt >= :since")
    Long sumTokensForTenant(Long tenantId, OffsetDateTime since);

    RequestLog findByRequestId(UUID requestId);
}
