package com.astra.observability.service;

import com.astra.observability.config.ClickHouseProperties;
import com.astra.observability.model.GatewayMetricEvent;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClickHousePublisherTest {

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.RequestBodySpec requestBodySpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

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

        publisher = new ClickHousePublisher(props, JsonMapper.builder().build(), RestClient.builder());
        ReflectionTestUtils.setField(publisher, "restClient", restClient);
    }

    @Test
    void flush_emptyBuffer_doesNotCallRestTemplate() {
        publisher.flush();
        verifyNoInteractions(restClient);
    }

    @Test
    void flush_singleEvent_postsToClickHouse() {
        stubRestClientChain();

        publisher.enqueue(sampleEvent("req-1", "anthropic"));
        publisher.flush();

        verify(requestBodySpec).body(any(String.class));
    }

    @Test
    void flush_multipleEvents_sendsAllInBody() {
        stubRestClientChain();

        publisher.enqueue(sampleEvent("req-1", "anthropic"));
        publisher.enqueue(sampleEvent("req-2", "openai"));
        publisher.enqueue(sampleEvent("req-3", "google"));
        publisher.flush();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestBodySpec).body(bodyCaptor.capture());

        String body = bodyCaptor.getValue();
        assertThat(body).contains("req-1").contains("req-2").contains("req-3");
        long lineCount = body.lines().filter(l -> !l.isBlank()).count();
        assertThat(lineCount).isEqualTo(3);
    }

    @Test
    void flush_clickHouseThrows_doesNotPropagate() {
        stubRestClientChain();
        when(responseSpec.toEntity(String.class)).thenThrow(new RuntimeException("ClickHouse unavailable"));

        publisher.enqueue(sampleEvent("req-1", "anthropic"));
        publisher.flush(); // must not throw
    }

    @Test
    void flush_afterException_bufferIsCleared() {
        stubRestClientChain();
        when(responseSpec.toEntity(String.class)).thenThrow(new RuntimeException("down"));

        publisher.enqueue(sampleEvent("req-1", "anthropic"));
        publisher.flush();

        reset(restClient);
        publisher.flush(); // buffer should be empty now
        verifyNoInteractions(restClient);
    }

    @Test
    void flush_insertUrlContainsDatabase() {
        stubRestClientChain();

        publisher.enqueue(sampleEvent("req-1", "anthropic"));
        publisher.flush();

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestBodyUriSpec).uri(urlCaptor.capture());

        assertThat(urlCaptor.getValue()).contains("astra").contains("gateway_metrics").contains("INSERT");
    }

    @Test
    @SuppressWarnings("unchecked")
    void flush_withBasicAuth_setsAuthHeader() {
        props.setUser("admin");
        props.setPassword("secret");
        publisher = new ClickHousePublisher(props, JsonMapper.builder().build(), RestClient.builder());
        ReflectionTestUtils.setField(publisher, "restClient", restClient);

        stubRestClientChain();

        publisher.enqueue(sampleEvent("req-1", "anthropic"));
        publisher.flush();

        ArgumentCaptor<Consumer<HttpHeaders>> headersCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(requestBodySpec).headers(headersCaptor.capture());

        HttpHeaders headers = new HttpHeaders();
        headersCaptor.getValue().accept(headers);
        assertThat(headers.getFirst("Authorization")).startsWith("Basic ");
    }

    @Test
    void flush_respectsBatchSize() {
        props.setBatchSize(2);
        publisher = new ClickHousePublisher(props, JsonMapper.builder().build(), RestClient.builder());
        ReflectionTestUtils.setField(publisher, "restClient", restClient);

        stubRestClientChain();

        publisher.enqueue(sampleEvent("req-1", "anthropic"));
        publisher.enqueue(sampleEvent("req-2", "openai"));
        publisher.enqueue(sampleEvent("req-3", "google")); // exceeds batch size

        publisher.flush(); // should send first 2
        publisher.flush(); // should send remaining 1

        verify(restClient, times(2)).post();
    }

    @SuppressWarnings("unchecked")
    private void stubRestClientChain() {
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any(Consumer.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(String.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(String.class)).thenReturn(ResponseEntity.ok("Ok."));
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
