package com.astra.governance.controller;

import com.astra.governance.entity.GovernancePolicy;
import com.astra.governance.repository.GovernancePolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/v1/governance-policies")
@RequiredArgsConstructor
public class GovernancePolicyController {

    private final GovernancePolicyRepository policyRepository;

    @PostMapping
    public ResponseEntity<GovernancePolicy> create(@RequestBody GovernancePolicy policy) {
        GovernancePolicy saved = policyRepository.save(policy);
        log.info("Created governance policy: {} ({})", saved.getName(), saved.getPolicyType());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping("/tenant/{tenantId}")
    public ResponseEntity<List<GovernancePolicy>> listForTenant(@PathVariable Long tenantId) {
        return ResponseEntity.ok(
            policyRepository.findByTenantIdAndIsEnabledTrueOrderByPriorityAsc(tenantId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Object> get(@PathVariable Long id) {
        return policyRepository.findById(id)
            .<ResponseEntity<Object>>map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Object> update(@PathVariable Long id, @RequestBody GovernancePolicy update) {
        return policyRepository.findById(id).map(existing -> {
            update.setId(existing.getId());
            return ResponseEntity.<Object>ok(policyRepository.save(update));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/disable")
    public ResponseEntity<Object> disable(@PathVariable Long id) {
        return policyRepository.findById(id).map(policy -> {
            policy.setIsEnabled(false);
            policyRepository.save(policy);
            return ResponseEntity.<Object>ok(Map.of("status", "disabled", "id", id));
        }).orElse(ResponseEntity.notFound().build());
    }
}
