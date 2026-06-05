package com.astra.routing.controller;

import com.astra.routing.entity.RoutingPolicy;
import com.astra.routing.repository.RoutingPolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/v1/routing-policies")
@RequiredArgsConstructor
public class RoutingPolicyController {

    private final RoutingPolicyRepository policyRepository;

    @PostMapping
    public ResponseEntity<RoutingPolicy> create(@RequestBody RoutingPolicy policy) {
        RoutingPolicy saved = policyRepository.save(policy);
        log.info("Created routing policy: {}", saved.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping("/tenant/{tenantId}")
    public ResponseEntity<List<RoutingPolicy>> listForTenant(@PathVariable Long tenantId) {
        return ResponseEntity.ok(
            policyRepository.findByTenantIdAndIsActiveTrueOrderByPriorityAsc(tenantId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Object> get(@PathVariable Long id) {
        return policyRepository.findById(id)
            .<ResponseEntity<Object>>map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Object> update(@PathVariable Long id, @RequestBody RoutingPolicy update) {
        return policyRepository.findById(id).map(existing -> {
            update.setId(existing.getId());
            return ResponseEntity.<Object>ok(policyRepository.save(update));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Object> deactivate(@PathVariable Long id) {
        return policyRepository.findById(id).map(policy -> {
            policy.setIsActive(false);
            policyRepository.save(policy);
            return ResponseEntity.<Object>ok(Map.of("status", "deactivated", "id", id));
        }).orElse(ResponseEntity.notFound().build());
    }
}
