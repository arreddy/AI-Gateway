package com.astra.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
public class ProviderService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(300);

    @Value("${providers.anthropic.api-key:${ANTHROPIC_API_KEY:}}")
    private String anthropicApiKey;

    @Value("${providers.anthropic.base-url:https://api.anthropic.com}")
    private String anthropicBaseUrl;

    @Value("${providers.openai.api-key:${OPENAI_API_KEY:}}")
    private String openaiApiKey;

    @Value("${providers.openai.base-url:https://api.openai.com}")
    private String openaiBaseUrl;

    @Autowired
    private WebClient.Builder webClientBuilder;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    public List<Map<String, Object>> listModels() {
        List<Map<String, Object>> models = new ArrayList<>();

        if (!anthropicApiKey.isEmpty()) {
            models.addAll(List.of(
                modelEntry("claude-opus-4-8", "anthropic", "Anthropic"),
                modelEntry("claude-sonnet-4-6", "anthropic", "Anthropic"),
                modelEntry("claude-haiku-4-5-20251001", "anthropic", "Anthropic")
            ));
        }

        if (!openaiApiKey.isEmpty()) {
            models.addAll(List.of(
                modelEntry("gpt-4o", "openai", "OpenAI"),
                modelEntry("gpt-4o-mini", "openai", "OpenAI"),
                modelEntry("gpt-3.5-turbo", "openai", "OpenAI")
            ));
        }

        if (models.isEmpty()) {
            models.addAll(List.of(
                modelEntry("claude-sonnet-4-6", "anthropic", "Anthropic"),
                modelEntry("gpt-4o", "openai", "OpenAI")
            ));
        }

        return models;
    }

    /** Called by GatewayController with the provider already resolved by the routing-engine. */
    public JsonNode chatCompletion(JsonNode request, String provider) throws Exception {
        String model    = request.has("model") ? request.get("model").asText() : "claude-sonnet-4-6";
        String cacheKey = "completion:" + Math.abs(request.toString().hashCode());

        boolean useCache = !request.has("stream") || !request.get("stream").asBoolean();
        if (useCache) {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.debug("Cache hit for completion request");
                JsonNode result = objectMapper.readTree(cached);
                ((ObjectNode) result).put("cached", true);
                return result;
            }
        }

        log.info("Routing to provider: {} for model: {}", provider, model);

        JsonNode response = switch (provider) {
            case "anthropic" -> callAnthropic(request, model);
            case "openai" -> callOpenAI(request, model);
            default -> fallbackResponse(model);
        };

        if (useCache && !response.has("error")) {
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(response), CACHE_TTL);
        }

        return response;
    }

    public String resolveProvider(String model) {
        if (model == null) return "anthropic";
        if (model.startsWith("claude")) return "anthropic";
        if (model.startsWith("gpt") || model.startsWith("o1") || model.startsWith("o3")) return "openai";
        return "anthropic";
    }

    private JsonNode callAnthropic(JsonNode request, String model) throws Exception {
        if (anthropicApiKey == null || anthropicApiKey.isEmpty()) {
            log.warn("Anthropic API key not configured");
            return fallbackResponse(model);
        }

        ObjectNode anthropicRequest = objectMapper.createObjectNode();
        anthropicRequest.put("model", model);
        anthropicRequest.put("max_tokens", request.has("max_tokens") ? request.get("max_tokens").asInt() : 1024);

        if (request.has("messages")) {
            List<Map<String, String>> messages = new ArrayList<>();
            String systemPrompt = null;
            for (JsonNode msg : request.get("messages")) {
                String role = msg.get("role").asText();
                String content = msg.has("content") ? msg.get("content").asText() : "";
                if ("system".equals(role)) {
                    systemPrompt = content;
                } else {
                    messages.add(Map.of("role", role, "content", content));
                }
            }
            if (systemPrompt != null) {
                anthropicRequest.put("system", systemPrompt);
            }
            anthropicRequest.set("messages", objectMapper.valueToTree(messages));
        }

        String responseBody = webClientBuilder.build()
            .post()
            .uri(anthropicBaseUrl + "/v1/messages")
            .header("x-api-key", anthropicApiKey)
            .header("anthropic-version", "2023-06-01")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(objectMapper.writeValueAsString(anthropicRequest))
            .retrieve()
            .bodyToMono(String.class)
            .block(Duration.ofSeconds(60));

        return toOpenAIFormat(objectMapper.readTree(responseBody), model);
    }

    private JsonNode callOpenAI(JsonNode request, String model) throws Exception {
        if (openaiApiKey == null || openaiApiKey.isEmpty()) {
            log.warn("OpenAI API key not configured");
            return fallbackResponse(model);
        }

        String responseBody = webClientBuilder.build()
            .post()
            .uri(openaiBaseUrl + "/v1/chat/completions")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + openaiApiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(objectMapper.writeValueAsString(request))
            .retrieve()
            .bodyToMono(String.class)
            .block(Duration.ofSeconds(60));

        return objectMapper.readTree(responseBody);
    }

    private JsonNode toOpenAIFormat(JsonNode anthropicResponse, String model) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        response.put("object", "chat.completion");
        response.put("created", System.currentTimeMillis() / 1000);
        response.put("model", model);

        String content = "";
        if (anthropicResponse.has("content") && anthropicResponse.get("content").isArray()) {
            JsonNode first = anthropicResponse.get("content").get(0);
            if (first != null && first.has("text")) {
                content = first.get("text").asText();
            }
        }

        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "assistant");
        message.put("content", content);

        ObjectNode choice = objectMapper.createObjectNode();
        choice.put("index", 0);
        choice.set("message", message);
        choice.put("finish_reason", "stop");
        response.set("choices", objectMapper.createArrayNode().add(choice));

        if (anthropicResponse.has("usage")) {
            int inputTokens = anthropicResponse.get("usage").path("input_tokens").asInt(0);
            int outputTokens = anthropicResponse.get("usage").path("output_tokens").asInt(0);
            ObjectNode usage = objectMapper.createObjectNode();
            usage.put("prompt_tokens", inputTokens);
            usage.put("completion_tokens", outputTokens);
            usage.put("total_tokens", inputTokens + outputTokens);
            response.set("usage", usage);
        }

        return response;
    }

    private JsonNode fallbackResponse(String model) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        response.put("object", "chat.completion");
        response.put("created", System.currentTimeMillis() / 1000);
        response.put("model", model);
        response.put("note", "Provider API key not configured — set ANTHROPIC_API_KEY or OPENAI_API_KEY");

        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "assistant");
        message.put("content", "Provider '" + resolveProvider(model) + "' is not configured. Please set the API key.");

        ObjectNode choice = objectMapper.createObjectNode();
        choice.put("index", 0);
        choice.set("message", message);
        choice.put("finish_reason", "stop");
        response.set("choices", objectMapper.createArrayNode().add(choice));
        return response;
    }

    private Map<String, Object> modelEntry(String id, String provider, String ownedBy) {
        return Map.of(
            "id", id,
            "object", "model",
            "created", System.currentTimeMillis() / 1000,
            "owned_by", ownedBy,
            "provider", provider
        );
    }
}
