package com.astra.gateway.interceptor;

import com.astra.gateway.client.AuthClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    @Mock private AuthClient          authClient;
    @Mock private HttpServletRequest  request;
    @Mock private HttpServletResponse response;

    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor(authClient, new ObjectMapper());
    }

    @Test
    void preHandle_healthEndpoint_bypassesAuth() throws Exception {
        when(request.getRequestURI()).thenReturn("/v1/health");
        boolean result = interceptor.preHandle(request, response, new Object());
        assertThat(result).isTrue();
        verifyNoInteractions(authClient);
    }

    @Test
    void preHandle_actuatorEndpoint_bypassesAuth() throws Exception {
        when(request.getRequestURI()).thenReturn("/actuator/health");
        boolean result = interceptor.preHandle(request, response, new Object());
        assertThat(result).isTrue();
        verifyNoInteractions(authClient);
    }

    @Test
    void preHandle_actuatorMetrics_bypassesAuth() throws Exception {
        when(request.getRequestURI()).thenReturn("/actuator/prometheus");
        boolean result = interceptor.preHandle(request, response, new Object());
        assertThat(result).isTrue();
        verifyNoInteractions(authClient);
    }

    @Test
    void preHandle_authAccepted_setsTenantIdAndReturnsTrue() throws Exception {
        when(request.getRequestURI()).thenReturn("/v1/chat/completions");
        when(request.getHeader("Authorization")).thenReturn("Bearer sk-astra-valid");
        when(authClient.validate("Bearer sk-astra-valid"))
            .thenReturn(new AuthClient.ValidationResult(true, "tenant-123", null));

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isTrue();
        verify(request).setAttribute(AuthInterceptor.TENANT_ID_ATTR, "tenant-123");
    }

    @Test
    void preHandle_authRejected_writes401AndReturnsFalse() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        when(request.getRequestURI()).thenReturn("/v1/chat/completions");
        when(request.getHeader("Authorization")).thenReturn(null);
        when(authClient.validate(null))
            .thenReturn(new AuthClient.ValidationResult(false, null, "missing_authorization_header"));
        when(response.getWriter()).thenReturn(pw);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertThat(result).isFalse();
        verify(response).setStatus(HttpStatus.UNAUTHORIZED.value());
        assertThat(sw.toString()).contains("Unauthorized");
    }

    @Test
    void preHandle_authRejected_writesReasonInBody() throws Exception {
        StringWriter sw = new StringWriter();
        when(request.getRequestURI()).thenReturn("/v1/models");
        when(request.getHeader("Authorization")).thenReturn("Bearer bad");
        when(authClient.validate("Bearer bad"))
            .thenReturn(new AuthClient.ValidationResult(false, null, "key_expired"));
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        interceptor.preHandle(request, response, new Object());
        assertThat(sw.toString()).contains("key_expired");
    }
}
