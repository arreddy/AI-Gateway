package com.astra.gateway.mcp.service;

import com.astra.gateway.mcp.model.McpToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP client using the HTTP+SSE transport (MCP spec 2024-11-05).
 *
 * Protocol flow per session:
 *   1. GET  {serverUrl}/mcp/sse              → SSE stream; server sends "endpoint" event
 *   2. POST {endpoint}  initialize           → response via SSE
 *   3. POST {endpoint}  tools/list           → tool list via SSE
 *   4. POST {endpoint}  tools/call           → tool result via SSE
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpGatewayClient {

    private static final int    REQUEST_TIMEOUT_SEC = 15;
    private static final int    CONNECT_TIMEOUT_MS  = 5_000;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper      objectMapper;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Discover all tools exposed by an MCP server. */
    public List<McpToolDefinition> discoverTools(String serverUrl, String serverId) {
        try (McpSession session = openSession(serverUrl)) {
            session.initialize();
            return session.listTools(serverId, serverUrl);
        } catch (Exception e) {
            log.warn("Tool discovery failed for {}: {}", serverUrl, e.getMessage());
            return List.of();
        }
    }

    /** Execute a named tool on an MCP server. Returns the content array node. */
    public JsonNode callTool(String serverUrl, String toolName, Map<String, Object> arguments) {
        try (McpSession session = openSession(serverUrl)) {
            session.initialize();
            return session.callTool(toolName, arguments);
        } catch (Exception e) {
            log.error("Tool call failed: {} on {}: {}", toolName, serverUrl, e.getMessage());
            ObjectNode err = objectMapper.createObjectNode();
            err.put("error", "Tool execution failed: " + e.getMessage());
            return err;
        }
    }

    // -------------------------------------------------------------------------
    // Session lifecycle
    // -------------------------------------------------------------------------

    private McpSession openSession(String serverUrl) throws Exception {
        CountDownLatch endpointLatch  = new CountDownLatch(1);
        AtomicReference<String> endpointRef = new AtomicReference<>();
        ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

        WebClient client = webClientBuilder
            .baseUrl(serverUrl)
            .defaultHeader("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
            .build();

        // Subscribe to the SSE stream in a virtual thread
        var subscription = client.get()
            .uri("/mcp/sse")
            .retrieve()
            .bodyToFlux(ServerSentEvent.class)
            .subscribe(
                event -> handleSseEvent(event, serverUrl, endpointRef, endpointLatch, pending),
                error -> {
                    log.warn("SSE stream error for {}: {}", serverUrl, error.getMessage());
                    endpointLatch.countDown(); // unblock connect on error
                }
            );

        if (!endpointLatch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            subscription.dispose();
            throw new TimeoutException("MCP server did not send endpoint event: " + serverUrl);
        }

        String endpoint = endpointRef.get();
        if (endpoint == null) {
            subscription.dispose();
            throw new IllegalStateException("No endpoint received from MCP server: " + serverUrl);
        }

        // Build the full messages URL
        String messagesUrl = endpoint.startsWith("http") ? endpoint : serverUrl + endpoint;
        RestTemplate restTemplate = buildRestTemplate();

        return new McpSession(messagesUrl, pending, subscription, restTemplate, objectMapper);
    }

    private void handleSseEvent(ServerSentEvent<?> event,
                                String serverUrl,
                                AtomicReference<String> endpointRef,
                                CountDownLatch endpointLatch,
                                ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending) {
        String eventName = event.event();
        Object data      = event.data();
        if (data == null) return;

        try {
            if ("endpoint".equals(eventName)) {
                endpointRef.set(data.toString());
                endpointLatch.countDown();
            } else if ("message".equals(eventName)) {
                JsonNode msg = objectMapper.readTree(data.toString());
                String id = msg.path("id").asText(null);
                if (id != null) {
                    CompletableFuture<JsonNode> future = pending.remove(id);
                    if (future != null) {
                        if (msg.has("error")) {
                            future.completeExceptionally(
                                new RuntimeException("MCP error: " + msg.get("error")));
                        } else {
                            future.complete(msg.path("result"));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse SSE event from {}: {}", serverUrl, e.getMessage());
        }
    }

    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(CONNECT_TIMEOUT_MS);
        f.setReadTimeout(REQUEST_TIMEOUT_SEC * 1_000);
        return new RestTemplate(f);
    }

    // -------------------------------------------------------------------------
    // McpSession — AutoCloseable handle for a single SSE connection
    // -------------------------------------------------------------------------

    static class McpSession implements AutoCloseable {

        private final String messagesUrl;
        private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending;
        private final reactor.core.Disposable subscription;
        private final RestTemplate restTemplate;

        McpSession(String messagesUrl,
                   ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending,
                   reactor.core.Disposable subscription,
                   RestTemplate restTemplate,
                   ObjectMapper objectMapper) {  // objectMapper kept in signature for callers
            this.messagesUrl  = messagesUrl;
            this.pending      = pending;
            this.subscription = subscription;
            this.restTemplate = restTemplate;
        }

        void initialize() throws Exception {
            sendRequest("initialize", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities",    Map.of(),
                "clientInfo",      Map.of("name", "astra-gateway", "version", "1.0.0")
            ));
            // Send the "initialized" notification (no response expected)
            Map<String, Object> notif = new LinkedHashMap<>();
            notif.put("jsonrpc", "2.0");
            notif.put("method",  "initialized");
            restTemplate.postForEntity(messagesUrl, notif, Void.class);
        }

        List<McpToolDefinition> listTools(String serverId, String serverUrl) throws Exception {
            JsonNode result = sendRequest("tools/list", null);
            List<McpToolDefinition> tools = new ArrayList<>();
            JsonNode toolsNode = result.path("tools");
            if (toolsNode.isArray()) {
                for (JsonNode t : toolsNode) {
                    McpToolDefinition def = new McpToolDefinition();
                    def.setName(t.path("name").asText());
                    def.setDescription(t.path("description").asText(""));
                    def.setInputSchema(t.path("inputSchema"));
                    def.setServerId(serverId);
                    def.setServerUrl(serverUrl);
                    tools.add(def);
                }
            }
            return tools;
        }

        JsonNode callTool(String name, Map<String, Object> arguments) throws Exception {
            JsonNode result = sendRequest("tools/call", Map.of(
                "name",      name,
                "arguments", arguments != null ? arguments : Map.of()
            ));
            // Return the content array (MCP tool result)
            return result.path("content");
        }

        private JsonNode sendRequest(String method, Object params) throws Exception {
            String id = UUID.randomUUID().toString();
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pending.put(id, future);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jsonrpc", "2.0");
            body.put("id",      id);
            body.put("method",  method);
            if (params != null) body.put("params", params);

            restTemplate.postForEntity(messagesUrl, body, Void.class);

            try {
                return future.get(REQUEST_TIMEOUT_SEC, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                pending.remove(id);
                throw new TimeoutException("Timeout waiting for MCP response to: " + method);
            }
        }

        @Override
        public void close() {
            subscription.dispose();
        }
    }
}
