package com.astra.gateway.mcp.service;

import com.astra.gateway.a2a.model.A2aAgentEntry;
import com.astra.gateway.a2a.model.A2aTask;
import com.astra.gateway.a2a.service.A2aAgentRegistry;
import com.astra.gateway.a2a.service.A2aGatewayClient;
import com.astra.gateway.mcp.model.McpServerEntry;
import com.astra.gateway.mcp.model.McpToolDefinition;
import com.astra.gateway.service.ProviderService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Integrates both MCP tools and A2A agents into the LLM request lifecycle.
 *
 * MCP tools  → injected as regular function tools (tools/call)
 * A2A agents → each skill exposed as a tool named "a2a__{agentId}__{skillId}"
 *              (tasks/send + poll to completion)
 *
 * Loop: LLM → tool_calls → execute (MCP or A2A) → append results → LLM → ...
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpToolOrchestrator {

    private static final int MAX_TOOL_ITERATIONS = 5;

    private final McpServerRegistry  mcpRegistry;
    private final McpGatewayClient   mcpClient;
    private final A2aAgentRegistry   a2aRegistry;
    private final A2aGatewayClient   a2aClient;
    private final ProviderService    providerService;
    private final ObjectMapper       objectMapper;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /** Whether any MCP/A2A tools are currently available to inject into requests. */
    public boolean hasTools() {
        return !gatherAllTools().isEmpty();
    }

    public JsonNode completeWithTools(JsonNode request, String provider) throws Exception {
        List<McpToolDefinition> tools = gatherAllTools();

        if (tools.isEmpty()) {
            return providerService.chatCompletion(request, provider);
        }

        ObjectNode augmented = augmentWithTools((ObjectNode) request.deepCopy(), tools);
        JsonNode response = providerService.chatCompletion(augmented, provider);

        int iterations = 0;
        while (hasToolCalls(response) && iterations++ < MAX_TOOL_ITERATIONS) {
            log.info("LLM requested {} tool call(s) — iteration {}", countToolCalls(response), iterations);
            augmented = applyToolResults(augmented, response, tools);
            response  = providerService.chatCompletion(augmented, provider);
        }

        return response;
    }

    // -------------------------------------------------------------------------
    // Tool collection — MCP + A2A
    // -------------------------------------------------------------------------

    List<McpToolDefinition> gatherAllTools() {
        List<McpToolDefinition> all = new ArrayList<>();
        all.addAll(gatherMcpTools());
        all.addAll(gatherA2aTools());
        log.debug("Total tools available: {} (MCP + A2A)", all.size());
        return all;
    }

    private List<McpToolDefinition> gatherMcpTools() {
        List<McpToolDefinition> all = new ArrayList<>();
        for (McpServerEntry server : mcpRegistry.listAll()) {
            List<McpToolDefinition> cached = mcpRegistry.getCachedTools(server.getId());
            if (cached.isEmpty()) {
                cached = mcpClient.discoverTools(server.getUrl(), server.getId());
                mcpRegistry.cacheTools(server.getId(), cached);
            }
            all.addAll(cached);
        }
        return all;
    }

    /**
     * Each A2A agent skill becomes an LLM tool.
     * Tool name convention: a2a__{agentId}__{skillId}
     * The single parameter is "input" — the natural-language task description.
     */
    private List<McpToolDefinition> gatherA2aTools() {
        List<McpToolDefinition> tools = new ArrayList<>();
        for (A2aAgentEntry agent : a2aRegistry.listAll()) {
            List<String> skills = agent.getSkills() != null ? agent.getSkills() : List.of();
            for (String skillId : skills) {
                McpToolDefinition tool = new McpToolDefinition();
                tool.setName(A2aAgentRegistry.toolName(agent.getId(), skillId));
                tool.setDescription(String.format(
                    "Delegate to A2A agent '%s' using skill '%s'. %s",
                    agent.getName(), skillId,
                    agent.getDescription() != null ? agent.getDescription() : ""));
                tool.setInputSchema(buildA2aInputSchema());
                tool.setServerId(agent.getId());
                tool.setServerUrl(agent.getUrl());
                tools.add(tool);
            }
            // If agent has no skills yet, expose a generic "ask" tool
            if (skills.isEmpty()) {
                McpToolDefinition tool = new McpToolDefinition();
                tool.setName(A2aAgentRegistry.toolName(agent.getId(), "ask"));
                tool.setDescription(String.format(
                    "Send a task to A2A agent '%s'. %s",
                    agent.getName(),
                    agent.getDescription() != null ? agent.getDescription() : ""));
                tool.setInputSchema(buildA2aInputSchema());
                tool.setServerId(agent.getId());
                tool.setServerUrl(agent.getUrl());
                tools.add(tool);
            }
        }
        return tools;
    }

    private JsonNode buildA2aInputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("input")
            .put("type", "string")
            .put("description", "The task description or question to send to the agent");
        schema.putArray("required").add("input");
        return schema;
    }

    // -------------------------------------------------------------------------
    // Request augmentation
    // -------------------------------------------------------------------------

    private ObjectNode augmentWithTools(ObjectNode request, List<McpToolDefinition> tools) {
        ArrayNode toolsArray = objectMapper.createArrayNode();
        for (McpToolDefinition t : tools) {
            ObjectNode fn = objectMapper.createObjectNode();
            fn.put("name", t.getName());
            fn.put("description", t.getDescription() != null ? t.getDescription() : "");
            fn.set("parameters", t.getInputSchema() != null
                ? t.getInputSchema()
                : objectMapper.createObjectNode().put("type", "object"));

            ObjectNode tool = objectMapper.createObjectNode();
            tool.put("type", "function");
            tool.set("function", fn);
            toolsArray.add(tool);
        }
        request.set("tools", toolsArray);
        request.put("tool_choice", "auto");
        return request;
    }

    // -------------------------------------------------------------------------
    // Tool execution + result injection
    // -------------------------------------------------------------------------

    private ObjectNode applyToolResults(ObjectNode request,
                                        JsonNode llmResponse,
                                        List<McpToolDefinition> tools) throws Exception {
        ArrayNode messages  = (ArrayNode) request.get("messages");
        JsonNode assistantMsg = llmResponse.path("choices").path(0).path("message");
        messages.add(assistantMsg.deepCopy());

        for (JsonNode tc : assistantMsg.path("tool_calls")) {
            String callId   = tc.path("id").asText();
            String toolName = tc.path("function").path("name").asText();
            String argsJson = tc.path("function").path("arguments").asText("{}");

            Map<String, Object> args = objectMapper.readValue(argsJson,
                objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));

            String resultText = A2aAgentRegistry.isA2aTool(toolName)
                ? executeA2aTool(toolName, args)
                : executeMcpTool(toolName, args);

            ObjectNode toolMsg = objectMapper.createObjectNode();
            toolMsg.put("role",         "tool");
            toolMsg.put("tool_call_id", callId);
            toolMsg.put("content",      resultText);
            messages.add(toolMsg);
        }

        return request;
    }

    // -------------------------------------------------------------------------
    // MCP tool dispatch
    // -------------------------------------------------------------------------

    private String executeMcpTool(String toolName, Map<String, Object> args) {
        log.info("Executing MCP tool: {}", toolName);
        Optional<McpServerEntry> serverOpt = mcpRegistry.findServerForTool(toolName);
        if (serverOpt.isEmpty()) {
            return "Error: No MCP server found for tool: " + toolName;
        }
        JsonNode result = mcpClient.callTool(serverOpt.get().getUrl(), toolName, args);
        return extractTextContent(result);
    }

    // -------------------------------------------------------------------------
    // A2A tool dispatch — send task to agent, wait for completion
    // -------------------------------------------------------------------------

    private String executeA2aTool(String toolName, Map<String, Object> args) {
        log.info("Executing A2A tool: {}", toolName);
        String[] parts = A2aAgentRegistry.parseA2aTool(toolName);
        if (parts == null) return "Error: Invalid A2A tool name: " + toolName;

        String agentId = parts[0];
        Optional<A2aAgentEntry> agentOpt = a2aRegistry.findById(agentId);
        if (agentOpt.isEmpty()) return "Error: A2A agent not found: " + agentId;

        String input = args.containsKey("input") ? args.get("input").toString() : args.toString();
        try {
            A2aTask task = a2aClient.sendTaskAndWait(agentOpt.get().getUrl(), input);
            String state = task.getStatus() != null ? task.getStatus().getState() : "unknown";
            if ("failed".equals(state) || "canceled".equals(state)) {
                return "Agent task " + state + " for: " + input;
            }
            String result = task.firstTextResult();
            return result != null ? result : "Agent completed task with no text output.";
        } catch (Exception e) {
            log.error("A2A task execution failed for agent {}: {}", agentId, e.getMessage());
            return "Error: A2A task failed — " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean hasToolCalls(JsonNode response) {
        return "tool_calls".equals(
            response.path("choices").path(0).path("finish_reason").asText(""));
    }

    private int countToolCalls(JsonNode response) {
        return response.path("choices").path(0)
            .path("message").path("tool_calls").size();
    }

    private String extractTextContent(JsonNode content) {
        if (content == null) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText())) sb.append(part.path("text").asText());
            }
            return sb.toString();
        }
        return content.toString();
    }
}
