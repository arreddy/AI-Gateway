package com.astra.gateway.client;

import com.astra.gateway.config.ServicesProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthClientTest {

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.RequestBodySpec requestBodySpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private ServicesProperties services;
    private AuthClient authClient;

    @BeforeEach
    void setUp() {
        services = buildServicesProperties(true);
        authClient = new AuthClient(services, RestClient.builder());
        ReflectionTestUtils.setField(authClient, "http", restClient);
    }

    @Test
    void validate_authDisabled_returnsOkWithDevTenant() {
        services = buildServicesProperties(false);
        authClient = new AuthClient(services, RestClient.builder());

        AuthClient.ValidationResult result = authClient.validate("any-token");
        assertThat(result.valid()).isTrue();
        assertThat(result.tenantId()).isEqualTo("dev-tenant");
        verifyNoInteractions(restClient);
    }

    @Test
    void validate_nullHeader_returnsDenied() {
        AuthClient.ValidationResult result = authClient.validate(null);
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo("missing_authorization_header");
    }

    @Test
    void validate_blankHeader_returnsDenied() {
        AuthClient.ValidationResult result = authClient.validate("   ");
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo("missing_authorization_header");
    }

    @Test
    void validate_validKey_returnsOkWithTenantId() {
        Map<String, Object> body = Map.of("valid", true, "tenant_id", "tenant-abc");
        ResponseEntity<Map<String, Object>> response = ResponseEntity.ok(body);
        stubRestClientChain();
        when(responseSpec.toEntity(any(ParameterizedTypeReference.class))).thenReturn(response);

        AuthClient.ValidationResult result = authClient.validate("Bearer sk-astra-key");
        assertThat(result.valid()).isTrue();
        assertThat(result.tenantId()).isEqualTo("tenant-abc");
    }

    @Test
    void validate_serverReturnsInvalid_returnsDeniedWithReason() {
        Map<String, Object> body = Map.of("valid", false, "reason", "key_expired");
        ResponseEntity<Map<String, Object>> response = ResponseEntity.ok(body);
        stubRestClientChain();
        when(responseSpec.toEntity(any(ParameterizedTypeReference.class))).thenReturn(response);

        AuthClient.ValidationResult result = authClient.validate("Bearer expired-key");
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo("key_expired");
    }

    @Test
    void validate_serverReturnsNullBody_returnsDenied() {
        ResponseEntity<Map<String, Object>> response = ResponseEntity.ok(null);
        stubRestClientChain();
        when(responseSpec.toEntity(any(ParameterizedTypeReference.class))).thenReturn(response);

        AuthClient.ValidationResult result = authClient.validate("Bearer some-key");
        assertThat(result.valid()).isFalse();
    }

    @Test
    void validate_unauthorizedException_returnsDenied() {
        stubRestClientChain();
        when(responseSpec.toEntity(any(ParameterizedTypeReference.class)))
            .thenThrow(HttpClientErrorException.Unauthorized.class);

        AuthClient.ValidationResult result = authClient.validate("Bearer bad-key");
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo("invalid_api_key");
    }

    @Test
    void validate_authServiceUnreachable_failsOpenWithUnknownTenant() {
        stubRestClientChain();
        when(responseSpec.toEntity(any(ParameterizedTypeReference.class)))
            .thenThrow(new RuntimeException("Connection refused"));

        AuthClient.ValidationResult result = authClient.validate("Bearer any-key");
        assertThat(result.valid()).isTrue();
        assertThat(result.tenantId()).isEqualTo("unknown-tenant");
    }

    @Test
    void validate_validResponse_noReasonOnSuccess() {
        Map<String, Object> body = Map.of("valid", true, "tenant_id", "t-1");
        stubRestClientChain();
        when(responseSpec.toEntity(any(ParameterizedTypeReference.class))).thenReturn(ResponseEntity.ok(body));

        AuthClient.ValidationResult result = authClient.validate("Bearer sk-astra-key");
        assertThat(result.reason()).isNull();
    }

    private void stubRestClientChain() {
        when(restClient.method(HttpMethod.POST)).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    }

    private ServicesProperties buildServicesProperties(boolean authEnabled) {
        ServicesProperties sp = new ServicesProperties();
        ServicesProperties.Auth auth = new ServicesProperties.Auth();
        auth.setEnabled(authEnabled);
        auth.setUrl("http://auth-service:8083");
        auth.setTimeoutMs(1000);
        sp.setAuth(auth);

        ServicesProperties.Routing routing = new ServicesProperties.Routing();
        routing.setUrl("http://routing-engine:8084");
        routing.setTimeoutMs(500);
        sp.setRouting(routing);

        ServicesProperties.Observability obs = new ServicesProperties.Observability();
        obs.setUrl("http://observability-service:8086");
        sp.setObservability(obs);

        return sp;
    }
}
