package com.astra.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doReturn;

@ExtendWith(MockitoExtension.class)
class ProviderServiceTest {

    @Mock private WebClient.Builder            webClientBuilder;
    @Mock private StringRedisTemplate          redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    // WebClient chain mocks
    @Mock private WebClient                         webClient;
    @Mock private WebClient.RequestBodyUriSpec      uriSpec;
    @Mock private WebClient.RequestBodySpec         bodySpec;
    @Mock private WebClient.ResponseSpec            responseSpec;

    private ProviderService providerService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        providerService = new ProviderService();
        ReflectionTestUtils.setField(providerService, "webClientBuilder", webClientBuilder);
        ReflectionTestUtils.setField(providerService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(providerService, "objectMapper", objectMapper);

        // Default: empty keys (no API key configured)
        ReflectionTestUtils.setField(providerService, "anthropicApiKey", "");
        ReflectionTestUtils.setField(providerService, "anthropicBaseUrl", "https://api.anthropic.com");
        ReflectionTestUtils.setField(providerService, "openaiApiKey", "");
        ReflectionTestUtils.setField(providerService, "openaiBaseUrl", "https://api.openai.com");
        ReflectionTestUtils.setField(providerService, "googleApiKey", "");
        ReflectionTestUtils.setField(providerService, "googleBaseUrl", "https://generativelanguage.googleapis.com/v1beta/openai");
    }

    // ── resolveProvider ───────────────────────────────────────────────────────

    @Test
    void resolveProvider_null_returnsAnthropic() {
        assertThat(providerService.resolveProvider(null)).isEqualTo("anthropic");
    }

    @Test
    void resolveProvider_claudePrefix_returnsAnthropic() {
        assertThat(providerService.resolveProvider("claude-sonnet-4-6")).isEqualTo("anthropic");
    }

    @Test
    void resolveProvider_gptPrefix_returnsOpenAI() {
        assertThat(providerService.resolveProvider("gpt-4o")).isEqualTo("openai");
    }

    @Test
    void resolveProvider_o1Prefix_returnsOpenAI() {
        assertThat(providerService.resolveProvider("o1-preview")).isEqualTo("openai");
    }

    @Test
    void resolveProvider_o3Prefix_returnsOpenAI() {
        assertThat(providerService.resolveProvider("o3-mini")).isEqualTo("openai");
    }

    @Test
    void resolveProvider_geminiPrefix_returnsGoogle() {
        assertThat(providerService.resolveProvider("gemini-2.5-flash")).isEqualTo("google");
    }

    @Test
    void resolveProvider_unknownModel_returnsAnthropic() {
        assertThat(providerService.resolveProvider("unknown-model")).isEqualTo("anthropic");
    }

    // ── listModels ────────────────────────────────────────────────────────────

    @Test
    void listModels_noKeys_returnsDefaultFallbackList() {
        List<Map<String, Object>> models = providerService.listModels();
        assertThat(models).isNotEmpty();
        List<String> ids = models.stream().map(m -> (String) m.get("id")).toList();
        assertThat(ids).contains("claude-sonnet-4-6", "gpt-4o");
    }

    @Test
    void listModels_anthropicKeySet_includesClaudeModels() {
        ReflectionTestUtils.setField(providerService, "anthropicApiKey", "sk-ant-test");
        List<Map<String, Object>> models = providerService.listModels();
        List<String> ids = models.stream().map(m -> (String) m.get("id")).toList();
        assertThat(ids).anyMatch(id -> id.startsWith("claude"));
    }

    @Test
    void listModels_openaiKeySet_includesGptModels() {
        ReflectionTestUtils.setField(providerService, "openaiApiKey", "sk-proj-test");
        List<Map<String, Object>> models = providerService.listModels();
        List<String> ids = models.stream().map(m -> (String) m.get("id")).toList();
        assertThat(ids).anyMatch(id -> id.startsWith("gpt"));
    }

    @Test
    void listModels_googleKeySet_includesGeminiModels() {
        ReflectionTestUtils.setField(providerService, "googleApiKey", "AIza-test");
        List<Map<String, Object>> models = providerService.listModels();
        List<String> ids = models.stream().map(m -> (String) m.get("id")).toList();
        assertThat(ids).anyMatch(id -> id.startsWith("gemini"));
    }

    @Test
    void listModels_eachEntry_hasRequiredFields() {
        ReflectionTestUtils.setField(providerService, "anthropicApiKey", "sk-ant-test");
        List<Map<String, Object>> models = providerService.listModels();
        for (Map<String, Object> model : models) {
            assertThat(model).containsKeys("id", "object", "owned_by", "provider");
        }
    }

    // ── chatCompletion — fallback (empty key) ─────────────────────────────────

    @Test
    void chatCompletion_anthropicEmptyKey_returnsFallbackResponse() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        JsonNode request = objectMapper.readTree("{\"model\":\"claude-sonnet-4-6\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        JsonNode result = providerService.chatCompletion(request, "anthropic");

        assertThat(result.get("note").asText()).contains("API key not configured");
    }

    @Test
    void chatCompletion_openaiEmptyKey_returnsFallbackResponse() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        JsonNode request = objectMapper.readTree("{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        JsonNode result = providerService.chatCompletion(request, "openai");

        assertThat(result.get("note").asText()).contains("API key not configured");
    }

    @Test
    void chatCompletion_googleEmptyKey_returnsFallbackResponse() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        JsonNode request = objectMapper.readTree("{\"model\":\"gemini-2.5-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        JsonNode result = providerService.chatCompletion(request, "google");

        assertThat(result.get("note").asText()).contains("API key not configured");
    }

    @Test
    void chatCompletion_unknownProvider_returnsFallback() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        JsonNode request = objectMapper.readTree("{\"model\":\"some-model\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        JsonNode result = providerService.chatCompletion(request, "unknown-provider");

        assertThat(result.has("note")).isTrue();
    }

    @Test
    void chatCompletion_cacheHit_returnsCachedResponse() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        String cachedJson = "{\"id\":\"cached-id\",\"object\":\"chat.completion\",\"model\":\"claude-sonnet-4-6\",\"choices\":[]}";
        when(valueOps.get(anyString())).thenReturn(cachedJson);

        JsonNode request = objectMapper.readTree("{\"model\":\"claude-sonnet-4-6\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        JsonNode result = providerService.chatCompletion(request, "anthropic");

        assertThat(result.get("id").asText()).isEqualTo("cached-id");
        assertThat(result.get("cached").asBoolean()).isTrue();
        verifyNoInteractions(webClientBuilder);
    }

    // ── chatCompletion — real API call (Anthropic) ────────────────────────────

    @Test
    void chatCompletion_anthropicKeySet_callsAnthropicApi() throws Exception {
        ReflectionTestUtils.setField(providerService, "anthropicApiKey", "sk-ant-test-key");
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        String anthropicResponse = "{\"id\":\"msg-1\",\"content\":[{\"type\":\"text\",\"text\":\"Hi!\"}],\"model\":\"claude-sonnet-4-6\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
        stubWebClientChain(anthropicResponse);

        JsonNode request = objectMapper.readTree("{\"model\":\"claude-sonnet-4-6\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        JsonNode result = providerService.chatCompletion(request, "anthropic");

        assertThat(result.get("object").asText()).isEqualTo("chat.completion");
        assertThat(result.path("choices").get(0).path("message").get("content").asText()).isEqualTo("Hi!");
    }

    @Test
    void chatCompletion_openaiKeySet_callsOpenAiApi() throws Exception {
        ReflectionTestUtils.setField(providerService, "openaiApiKey", "sk-proj-test-key");
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        String openaiResponse = "{\"id\":\"cmpl-1\",\"object\":\"chat.completion\",\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Hello!\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
        stubWebClientChain(openaiResponse);

        JsonNode request = objectMapper.readTree("{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        JsonNode result = providerService.chatCompletion(request, "openai");

        assertThat(result.get("id").asText()).isEqualTo("cmpl-1");
    }

    @Test
    void chatCompletion_withSystemMessage_extractsSystemPrompt() throws Exception {
        ReflectionTestUtils.setField(providerService, "anthropicApiKey", "sk-ant-test-key");
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        String anthropicResponse = "{\"id\":\"msg-1\",\"content\":[{\"type\":\"text\",\"text\":\"OK\"}],\"model\":\"claude-sonnet-4-6\",\"usage\":{\"input_tokens\":20,\"output_tokens\":5}}";
        stubWebClientChain(anthropicResponse);

        JsonNode request = objectMapper.readTree("{\"model\":\"claude-sonnet-4-6\",\"messages\":[{\"role\":\"system\",\"content\":\"Be helpful\"},{\"role\":\"user\",\"content\":\"hi\"}]}");
        JsonNode result = providerService.chatCompletion(request, "anthropic");

        assertThat(result.get("object").asText()).isEqualTo("chat.completion");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubWebClientChain(String responseBody) {
        when(webClientBuilder.build()).thenReturn(webClient);
        when(webClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        // Use doReturn to avoid generic wildcard capture mismatch on RequestHeadersSpec<?>
        doReturn(bodySpec).when(bodySpec).header(anyString(), anyString());
        doReturn(bodySpec).when(bodySpec).contentType(any());
        doReturn(bodySpec).when(bodySpec).bodyValue(any());
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(responseBody));
    }
}
