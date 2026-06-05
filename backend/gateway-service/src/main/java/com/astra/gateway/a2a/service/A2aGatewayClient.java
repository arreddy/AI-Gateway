package com.astra.gateway.a2a.service;

import com.astra.gateway.a2a.model.A2aAgentEntry;
import com.astra.gateway.a2a.model.A2aTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.retry.support.RetryTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.TimeoutException;

/**
 * A2A protocol client (spec 2024-11-05).
 *
 * Retry policy (applied to every HTTP call):
 *   - Max attempts : 3
 *   - Retried on   : ResourceAccessException (timeout/refused), HttpServerErrorException (5xx)
 *   - Backoff      : 500 ms → 1 000 ms → 2 000 ms (exponential, max 5 s)
 *
 * Polling (sendTaskAndWait):
 *   - Up to 50 polls (≈30 s) with exponential backoff starting at 300 ms.
 */
@Slf4j
@Service
public class A2aGatewayClient {

    private static final int HTTP_TIMEOUT_MS   = 10_000;
    private static final int MAX_POLL_ATTEMPTS = 50;

    private static final Set<String> TERMINAL_STATES =
        Set.of("completed", "failed", "canceled");

    private final ObjectMapper  objectMapper;
    private final RetryTemplate retryTemplate;
    private final RestTemplate  http;

    public A2aGatewayClient(ObjectMapper objectMapper) {
        this.objectMapper  = objectMapper;
        this.http          = buildHttp();
        this.retryTemplate = new RetryTemplateBuilder()
            .maxAttempts(3)
            .exponentialBackoff(500, 2.0, 5_000)
            .retryOn(ResourceAccessException.class)
            .retryOn(HttpServerErrorException.class)
            .build();
    }

    // -------------------------------------------------------------------------
    // Agent card discovery (retried)
    // -------------------------------------------------------------------------

    public A2aAgentEntry discoverCard(A2aAgentEntry entry) {
        try {
            Map<String, Object> card = retryTemplate.execute((RetryCallback<Map<String, Object>, Exception>) ctx -> {
                if (ctx.getRetryCount() > 0) {
                    log.warn("Retrying agent card discovery for {} (attempt {})",
                        entry.getUrl(), ctx.getRetryCount() + 1);
                }
                ResponseEntity<Map<String, Object>> resp = http.exchange(
                    entry.getUrl() + "/.well-known/agent.json",
                    HttpMethod.GET, null,
                    new ParameterizedTypeReference<>() {});
                return resp.getBody();
            });

            if (card == null) return entry;
            entry.setSkills(extractSkillIds(card));
            entry.setCapabilities(extractCapabilities(card));
            if (entry.getDescription() == null && card.containsKey("description")) {
                entry.setDescription(card.get("description").toString());
            }
            log.info("Discovered A2A agent card: {} — skills: {}", entry.getName(), entry.getSkills());
        } catch (Exception e) {
            log.warn("Agent card discovery failed after retries for {}: {}", entry.getUrl(), e.getMessage());
        }
        return entry;
    }

    // -------------------------------------------------------------------------
    // Task lifecycle
    // -------------------------------------------------------------------------

    public A2aTask sendTaskAndWait(String agentUrl, String message) throws Exception {
        return sendTaskAndWait(agentUrl, message, null);
    }

    /**
     * Send a task and block until it reaches a terminal state.
     * Polls with exponential backoff: 300 ms → 600 ms → 1200 ms → … (max 5 s).
     */
    public A2aTask sendTaskAndWait(String agentUrl, String message, String sessionId)
            throws Exception {
        String taskId = UUID.randomUUID().toString();
        A2aTask task  = sendTask(agentUrl, taskId, message, sessionId);

        long delayMs = 300;
        for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
            String state = task.getStatus() != null ? task.getStatus().getState() : "unknown";
            if (TERMINAL_STATES.contains(state)) {
                log.debug("A2A task {} reached state '{}' after {} polls", taskId, state, i + 1);
                return task;
            }
            Thread.sleep(delayMs);
            delayMs = Math.min(delayMs * 2, 5_000);
            task = getTask(agentUrl, taskId);
        }
        throw new TimeoutException("A2A task did not complete within timeout: " + taskId);
    }

    /** Send a task — retried on transient failures. */
    public A2aTask sendTask(String agentUrl, String taskId,
                            String message, String sessionId) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", taskId);
        if (sessionId != null) params.put("sessionId", sessionId);
        params.put("message", Map.of(
            "role",  "user",
            "parts", List.of(Map.of("type", "text", "text", message))
        ));
        JsonNode result = rpc(agentUrl + "/a2a", "tasks/send", params);
        return objectMapper.convertValue(result, A2aTask.class);
    }

    /** Poll task state — retried on transient failures. */
    public A2aTask getTask(String agentUrl, String taskId) throws Exception {
        JsonNode result = rpc(agentUrl + "/a2a", "tasks/get", Map.of("id", taskId));
        return objectMapper.convertValue(result, A2aTask.class);
    }

    /** Cancel a task — retried on transient failures. */
    public A2aTask cancelTask(String agentUrl, String taskId) throws Exception {
        JsonNode result = rpc(agentUrl + "/a2a", "tasks/cancel", Map.of("id", taskId));
        return objectMapper.convertValue(result, A2aTask.class);
    }

    // -------------------------------------------------------------------------
    // JSON-RPC 2.0 transport — all calls wrapped in RetryTemplate
    // -------------------------------------------------------------------------

    private JsonNode rpc(String url, String method, Object params) throws Exception {
        return retryTemplate.execute((RetryCallback<JsonNode, Exception>) ctx -> {
            if (ctx.getRetryCount() > 0) {
                log.warn("Retrying A2A RPC {} → {} (attempt {})",
                    method, url, ctx.getRetryCount() + 1);
            }

            String id = UUID.randomUUID().toString();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jsonrpc", "2.0");
            body.put("method",  method);
            body.put("id",      id);
            body.put("params",  params);

            JsonNode response = http.postForObject(url, body, JsonNode.class);
            if (response == null) {
                throw new IllegalStateException("Empty response from A2A agent: " + url);
            }
            if (response.has("error")) {
                // Application-level A2A error — do NOT retry
                throw new RuntimeException("A2A protocol error: " + response.get("error"));
            }
            return response.path("result");
        });
    }

    private RestTemplate buildHttp() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(HTTP_TIMEOUT_MS);
        f.setReadTimeout(HTTP_TIMEOUT_MS);
        return new RestTemplate(f);
    }

    // -------------------------------------------------------------------------
    // Agent card parsing
    // -------------------------------------------------------------------------

    private List<String> extractSkillIds(Map<String, Object> card) {
        Object skillsObj = card.get("skills");
        if (!(skillsObj instanceof List<?> list)) return List.of();
        List<String> ids = new ArrayList<>();
        for (Object s : list) {
            if (s instanceof Map<?, ?> skill && skill.containsKey("id")) {
                ids.add(skill.get("id").toString());
            }
        }
        return ids;
    }

    private List<String> extractCapabilities(Map<String, Object> card) {
        if (!(card.get("capabilities") instanceof Map<?, ?> raw)) return List.of();
        List<String> result = new ArrayList<>();
        if (Boolean.TRUE.equals(raw.get("streaming")))              result.add("streaming");
        if (Boolean.TRUE.equals(raw.get("pushNotifications")))      result.add("pushNotifications");
        if (Boolean.TRUE.equals(raw.get("stateTransitionHistory"))) result.add("stateTransitionHistory");
        return result;
    }
}
