package com.astra.governance.repository;

import com.astra.governance.entity.GovernancePolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GovernancePolicyRepository extends JpaRepository<GovernancePolicy, Long> {
    List<GovernancePolicy> findByTenantIdAndIsEnabledTrueOrderByPriorityAsc(Long tenantId);
    List<GovernancePolicy> findByTenantIdAndPolicyTypeAndIsEnabledTrue(Long tenantId, String policyType);
    List<GovernancePolicy> findByIsEnabledTrueOrderByPriorityAsc(); // system-wide policies (tenantId = null)
}
