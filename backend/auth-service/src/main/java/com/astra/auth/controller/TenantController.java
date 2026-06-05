package com.astra.auth.controller;

import com.astra.auth.entity.Tenant;
import com.astra.auth.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @PostMapping
    public ResponseEntity<Object> create(@RequestBody Map<String, Object> request) {
        try {
            Tenant tenant = tenantService.create(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(tenant);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<Tenant>> list() {
        return ResponseEntity.ok(tenantService.findAll());
    }

    @GetMapping("/{tenantId}")
    public ResponseEntity<Object> get(@PathVariable UUID tenantId) {
        return tenantService.findById(tenantId)
            .<ResponseEntity<Object>>map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{tenantId}/status")
    public ResponseEntity<Object> updateStatus(
            @PathVariable UUID tenantId,
            @RequestBody Map<String, String> body) {
        try {
            Tenant updated = tenantService.updateStatus(tenantId, body.get("status"));
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/{tenantId}/tier")
    public ResponseEntity<Object> updateTier(
            @PathVariable UUID tenantId,
            @RequestBody Map<String, Object> body) {
        try {
            Tenant updated = tenantService.updateTier(
                tenantId,
                (String) body.get("tier"),
                body.containsKey("rate_limit_rpm") ? ((Number) body.get("rate_limit_rpm")).intValue() : null,
                body.containsKey("rate_limit_tpm") ? ((Number) body.get("rate_limit_tpm")).intValue() : null
            );
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
