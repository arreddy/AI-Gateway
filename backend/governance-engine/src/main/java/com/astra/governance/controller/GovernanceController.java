package com.astra.governance.controller;

import com.astra.governance.service.ContentGovernanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/v1/governance")
public class GovernanceController {

    @Autowired
    private ContentGovernanceService governanceService;

    @GetMapping("/health")
    public ResponseEntity<Object> health() {
        return ResponseEntity.ok(Map.of("status", "healthy", "service", "governance-engine"));
    }

    @PostMapping("/check")
    public ResponseEntity<Object> checkGovernance(@RequestBody GovernanceRequest request) {
        log.info("Checking governance policies for content type: {}", request.type);
        Map<String, Object> result = governanceService.checkContent(request.content, request.type);
        boolean safe = Boolean.TRUE.equals(result.get("safe"));
        // 422 Unprocessable Entity when content fails governance
        return safe ? ResponseEntity.ok(result) : ResponseEntity.unprocessableEntity().body(result);
    }

    @PostMapping("/policy/validate")
    public ResponseEntity<Object> validatePolicy(@RequestBody PolicyValidationRequest request) {
        log.info("Validating policy: {}", request.policy);
        Map<String, Object> result = governanceService.validatePolicy(request.policy, request.data);
        return ResponseEntity.ok(result);
    }

    static class GovernanceRequest {
        public String content;
        public String type; // prompt, response
    }

    static class PolicyValidationRequest {
        public String policy;
        public Object data;
    }
}
