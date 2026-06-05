package com.astra.gateway.a2a.repository;

import com.astra.gateway.a2a.entity.A2aAgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface A2aAgentJpaRepository extends JpaRepository<A2aAgentEntity, String> {
    Optional<A2aAgentEntity> findByUrl(String url);
    boolean existsByUrl(String url);
}
