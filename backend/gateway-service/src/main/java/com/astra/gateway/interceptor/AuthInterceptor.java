package com.astra.gateway.interceptor;

import com.astra.gateway.client.AuthClient;
import com.astra.gateway.client.AuthClient.ValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** Request attribute key — set here, read by GatewayController. */
    public static final String TENANT_ID_ATTR = "tenantId";

    private final AuthClient authClient;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String path = request.getRequestURI();

        // Skip auth for health / actuator endpoints
        if (path.startsWith("/actuator") || path.equals("/v1/health")) {
            return true;
        }

        String authorization = request.getHeader("Authorization");
        ValidationResult result = authClient.validate(authorization);

        if (!result.valid()) {
            log.warn("Auth rejected: {} — {}", path, result.reason());
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(
                Map.of("error", "Unauthorized", "reason", result.reason())));
            return false;
        }

        // Store tenantId so the controller can thread it to observability
        request.setAttribute(TENANT_ID_ATTR, result.tenantId());
        return true;
    }
}
