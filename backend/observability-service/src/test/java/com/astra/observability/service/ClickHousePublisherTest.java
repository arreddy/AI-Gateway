package com.astra.observability.service;

import com.astra.observability.config.ClickHouseProperties;
import com.astra.observability.model.GatewayMetricEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClickHousePublisherTest {

    @Mock
    private RestTemplate restTemplate;

    private ClickHouseProperties props;
    private ClickHousePublisher publisher;

    @BeforeEach
    void setUp() {
        props = new ClickHouseProperties();
        props.setUrl("http://clickhouse:8123");
        props.setDatabase("astra");
        props.setTable("gateway_metrics");
        props.setUser("default");
        props.setPassword("");
        props.setBatchSize(500);

        publisher = new ClickHousePublisher(props, new ObjectMapper(), restTemplate);
    }

    @Test
    void flush_emptyBuffer_doesNotCallRestTemplate() {
        publisher.flush();
        verifyNoInteractions(restTemplate);
    }

    @Test
    void flush_singleEvent_postsToClickHouse() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("Ok."));

        publisher.enqueue(sampleEvent("req-1", "anthropic"));
        publisher.flush();

        verify(restTemplate).postForEntity(anyString(), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void flush_multipleEvents_sendsAllInBody() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("Ok."));

        publisher.enqueue(sampleEvent("req-1", "anthropic"));
        publisher.enqueue(sampleEvent("req-2", "openai"));
        publisher.enqueue(sampleEvent("req-3", "google"));
        publisher.flush();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(anyString(), captor.capture(), eq(String.class));

        String body = captor.getValue().getBody();
        assertThat(body).contains("req-1").contains("req-2").contains("req-3");
        long lineCount = body.lines().filter(l -> !l.isBlank()).count();
        assertThat(lineCount).isEqualTo(3);
    }

    @Test
    void flush_clickHouseThrows_doesNotPropagate() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenThrow(new RuntimeException("ClickHouse unavailable"));

        publisher.enqueue(sampleEvent("req-1", "anthropic"));
        publisher.flush(); // must not throw
    }

    @Test
    void flush_afterException_bufferIsCleared() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenThrow(new RuntimeException("down"));

        publisher.enqueue(sampleEvent("req-1", "anthropic"));
        publisher.flush();

        reset(restTemplate);
        publisher.flush(); // buffer should be empty now
        verifyNoInteractions(restTemplate);
    }

    @Test
    void flush_insertUrlContainsDatabase() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("Ok."));

        publisher.enqueue(sampleEvent("req-1", "anthropic"));
        publisher.flush();

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).postForEntity(urlCaptor.capture(), any(), eq(String.class));

        assertThat(urlCaptor.getValue()).contains("astra").contains("gateway_metrics").contains("INSERT");
    }

    @Test
    void flush_withBasicAuth_setsAuthHeader() {
        props.setUser("admin");
        props.setPassword("secret");
        publisher = new ClickHousePublisher(props, new ObjectMapper(), restTemplate);

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("Ok."));

        publisher.enqueue(sampleEvent("req-1", "anthropic"));
        publisher.flush();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(anyString(), captor.capture(), eq(String.class));
        assertThat(captor.getValue().getHeaders().getFirst("Authorization")).startsWith("Basic ");
    }

    @Test
    void flush_respectsBatchSize() {
        props.setBatchSize(2);
        publisher = new ClickHousePublisher(props, new ObjectMapper(), restTemplate);

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("Ok."));

        publisher.enqueue(sampleEvent("req-1", "anthropic"));
        publisher.enqueue(sampleEvent("req-2", "openai"));
        publisher.enqueue(sampleEvent("req-3", "google")); // exceeds batch size

        publisher.flush(); // should send first 2
        publisher.flush(); // should send remaining 1

        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(String.class));
    }

    private GatewayMetricEvent sampleEvent(String requestId, String provider) {
        return GatewayMetricEvent.builder()
            .requestId(requestId)
            .provider(provider)
            .model("test-model")
            .latencyMs(100)
            .inputTokens(50)
            .outputTokens(30)
            .totalTokens(80)
            .costUsd(0.001)
            .status("success")
            .tenantId("tenant-1")
            .build();
    }
}
