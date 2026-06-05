package com.astra.gateway.mcp.repository;

import com.astra.gateway.mcp.entity.McpServerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface McpServerJpaRepository extends JpaRepository<McpServerEntity, String> {
    Optional<McpServerEntity> findByUrl(String url);
    boolean existsByUrl(String url);
}
