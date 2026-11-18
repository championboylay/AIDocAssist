package com.sip.aidocassist.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sip.aidocassist.ollama.OllamaClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The chat model for answers and follow-up rewrites (owner's decision 2026-10-24): DeepSeek when
 * DEEPSEEK_API_KEY is set, otherwise Ollama as before. Same settings as the Ollama call: thinking
 * off, temperature 0.1, a JSON reply. DeepSeek's JSON mode takes no schema, so the schema is put
 * in the system prompt; the reply is still checked by the callers (citation validation).
 */
@Component
public class Chat {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OllamaClient ollama;
    private final String key;
    private final String model;
    private final RestClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public Chat(OllamaClient ollama, @Value("${deepseek.url}") String url, @Value("${deepseek.api-key}") String key,
                @Value("${deepseek.model}") String model) {
        this.ollama = ollama;
        this.key = key.strip();
        this.model = model;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofMinutes(3));
        this.http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    /** Ollama only (tests). */
    public Chat(OllamaClient ollama) {
        this(ollama, "http://unused", "", "unused");
    }

    private record Response(List<Choice> choices) {}

    private record Choice(Message message) {}

    private record Message(String content) {}

    /** One structured call; returns the reply parsed as JSON. */
    public JsonNode chat(String system, String user, Map<String, Object> schema) {
        if (key.isEmpty()) return ollama.chat(system, user, schema);
        String withSchema;
        try {
            withSchema = system + "\n\nReply with one JSON object only, matching this JSON schema:\n" + JSON.writeValueAsString(schema);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var body = Map.of("model", model, "stream", false, "temperature", 0.1, "max_tokens", 4096,
                "thinking", Map.of("type", "disabled"), "response_format", Map.of("type", "json_object"),
                "messages", List.of(Map.of("role", "system", "content", withSchema), Map.of("role", "user", "content", user)));
        Response r = http.post().uri("/chat/completions").header("Authorization", "Bearer " + key).body(body).retrieve().body(Response.class);
        String content = r == null || r.choices() == null || r.choices().isEmpty() ? null : r.choices().get(0).message().content();
        try {
            return JSON.readTree(content);
        } catch (Exception e) {
            throw new IllegalStateException("the model's reply was not the requested JSON: " + content, e);
        }
    }
}
