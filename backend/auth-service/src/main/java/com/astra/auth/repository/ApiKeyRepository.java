package com.astra.auth.repository;

import com.astra.auth.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {
    Optional<ApiKey> findByKeyHash(String keyHash);
    Optional<ApiKey> findByExternalId(UUID externalId);
    List<ApiKey> findByTenantId(Long tenantId);
    List<ApiKey> findByTenantIdAndStatus(Long tenantId, String status);

    @Modifying
    @Query("UPDATE ApiKey k SET k.totalRequests = k.totalRequests + 1, k.totalTokens = k.totalTokens + :tokens WHERE k.keyHash = :keyHash")
    void incrementUsage(String keyHash, long tokens);
}
