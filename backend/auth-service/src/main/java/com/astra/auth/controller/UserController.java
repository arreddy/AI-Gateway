package com.astra.auth.controller;

import com.astra.auth.entity.User;
import com.astra.auth.service.UserService;
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
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/v1/tenants/{tenantId}/users")
    public ResponseEntity<Object> register(
            @PathVariable UUID tenantId,
            @RequestBody Map<String, Object> request) {
        try {
            Map<String, Object> user = userService.register(tenantId, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(user);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/v1/tenants/{tenantId}/users")
    public ResponseEntity<List<User>> list(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(userService.listForTenant(tenantId));
    }

    /** Login — returns a signed JWT + user details. */
    @PostMapping("/v1/auth/login")
    public ResponseEntity<Object> login(@RequestBody Map<String, Object> request) {
        try {
            Map<String, Object> result = userService.login(request);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", e.getMessage()));
        }
    }
}
