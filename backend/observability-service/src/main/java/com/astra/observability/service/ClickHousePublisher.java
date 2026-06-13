package com.astra.observability.service;

import com.astra.observability.config.ClickHouseProperties;
import com.astra.observability.model.GatewayMetricEvent;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Buffers GatewayMetricEvents in-memory and flushes them to ClickHouse
 * in batches using the native HTTP interface with JSONEachRow format.
 *
 * ClickHouse HTTP endpoint:
 *   POST http://clickhouse:8123/?query=INSERT INTO astra.gateway_metrics FORMAT JSONEachRow
 *   Body: one JSON object per line (newline-delimited)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickHousePublisher {

    private final ClickHouseProperties props;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    private final ConcurrentLinkedQueue<GatewayMetricEvent> buffer = new ConcurrentLinkedQueue<>();

    /** Enqueue an event — called from MetricsService, never blocks the caller. */
    public void enqueue(GatewayMetricEvent event) {
        buffer.offer(event);
    }

    /**
     * Drain the buffer and POST to ClickHouse every flushIntervalMs.
     * If ClickHouse is unreachable the events are logged and dropped
     * (not re-queued) to prevent unbounded memory growth.
     */
    @Scheduled(fixedDelayString = "${clickhouse.flush-interval-ms:5000}")
    public void flush() {
        if (buffer.isEmpty()) return;

        List<GatewayMetricEvent> batch = new ArrayList<>(props.getBatchSize());
        GatewayMetricEvent event;
        while (batch.size() < props.getBatchSize() && (event = buffer.poll()) != null) {
            batch.add(event);
        }

        if (batch.isEmpty()) return;

        try {
            String body = buildJsonEachRow(batch);
            String url = buildInsertUrl();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_PLAIN);
            if (!props.getUser().equals("default") || !props.getPassword().isEmpty()) {
                headers.setBasicAuth(props.getUser(), props.getPassword());
            }

            restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
            log.debug("Flushed {} metric events to ClickHouse", batch.size());
        } catch (Exception e) {
            log.warn("ClickHouse flush failed ({} events dropped): {}", batch.size(), e.getMessage());
        }
    }

    private String buildJsonEachRow(List<GatewayMetricEvent> batch) {
        StringBuilder sb = new StringBuilder();
        for (GatewayMetricEvent ev : batch) {
            try {
                sb.append(objectMapper.writeValueAsString(ev)).append('\n');
            } catch (Exception e) {
                log.warn("Failed to serialise metric event: {}", e.getMessage());
            }
        }
        return sb.toString();
    }

    private String buildInsertUrl() {
        String query = String.format(
            "INSERT INTO %s.%s FORMAT JSONEachRow",
            props.getDatabase(), props.getTable());
        return UriComponentsBuilder.fromUriString(props.getUrl())
            .queryParam("query", query)
            .build().toUriString();
    }
}
