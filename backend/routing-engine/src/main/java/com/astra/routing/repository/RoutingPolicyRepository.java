package com.astra.routing.repository;

import com.astra.routing.entity.RoutingPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoutingPolicyRepository extends JpaRepository<RoutingPolicy, Long> {
    List<RoutingPolicy> findByTenantIdAndIsActiveTrueOrderByPriorityAsc(Long tenantId);
    Optional<RoutingPolicy> findByTenantIdAndName(Long tenantId, String name);
}
