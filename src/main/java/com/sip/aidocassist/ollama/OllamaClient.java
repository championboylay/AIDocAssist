package com.sip.aidocassist.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class OllamaClient {

    /** Kept at SKB's pgvector limit so results compare directly with SKB's (see CLAUDE.md). */
    public static final int DIM = 2000;

    /** Ollama's default context (2-4k tokens) would silently cut off most of the evidence (CLAUDE.md). */
    static final int NUM_CTX = 16384;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;
    private final String embedModel;
    private final String chatModel;

    @Autowired
    public OllamaClient(@Value("${ollama.url}") String url, @Value("${embed.model}") String embedModel,
                        @Value("${chat.model}") String chatModel) {
        // Short connect timeout: an unreachable Ollama fails in seconds. Long read timeout: a big
        // batch on a busy server is slow. Same as SKB.
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofMinutes(10));
        this.http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        this.embedModel = embedModel;
        this.chatModel = chatModel;
    }

    public OllamaClient(String url, String embedModel) {
        this(url, embedModel, "unused");
    }

    private record ChatResponse(Message message) {}

    private record Message(String content) {}

    /**
     * One structured chat call to the 4b model (CLAUDE.md "Ollama"): think=false (it otherwise
     * spends ~20 s thinking), temperature 0.1, num_ctx 16384, and {@code format} = a JSON schema, so
     * the reply comes back machine-checkable. Returns the reply parsed as JSON.
     */
    public JsonNode chat(String system, String user, Map<String, Object> schema) {
        var body = Map.of("model", chatModel, "stream", false, "think", false, "format", schema,
                "options", Map.of("temperature", 0.1, "num_ctx", NUM_CTX),
                "messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        ChatResponse r = http.post().uri("/api/chat").body(body).retrieve().body(ChatResponse.class);
        try {
            return JSON.readTree(r.message().content());
        } catch (Exception e) {
            throw new IllegalStateException("the model's reply was not the requested JSON: " + r.message().content(), e);
        }
    }

    /**
     * Qwen3-Embedding is trained with an instruction on the query side only; documents are embedded
     * as they are (CLAUDE.md, SKB retrieval/vector.py). Without it query and passage vectors compare worse.
     */
    public static final String QUERY_INSTRUCTION = "Instruct: Given a question, retrieve passages from internal documents that answer it\nQuery: ";

    /** A question's embedding, with the query instruction. */
    public float[] embedQuery(String question) {
        return embed(List.of(QUERY_INSTRUCTION + question))[0];
    }

    /** Embeddings truncated to {@link #DIM} and L2-renormalized, ready to store or compare. */
    public float[][] embed(List<String> texts) {
        List<List<Double>> raw = embedRaw(texts);
        float[][] out = new float[raw.size()][];
        for (int i = 0; i < raw.size(); i++) out[i] = truncateNormalize(raw.get(i), DIM);
        return out;
    }

    private record EmbedResponse(List<List<Double>> embeddings) {}

    /** Ollama's full-length vectors (4096 for qwen3-embedding:8b). */
    List<List<Double>> embedRaw(List<String> texts) {
        return http.post().uri("/api/embed")
                .body(Map.of("model", embedModel, "input", texts))
                .retrieve().body(EmbedResponse.class).embeddings();
    }

    /** A vector as stored in knowledge_records.embedding: float32, little-endian, 4 bytes per value. */
    public static byte[] toBytes(float[] v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float x : v) b.putFloat(x);
        return b.array();
    }

    public static float[] fromBytes(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[bytes.length / 4];
        for (int i = 0; i < v.length; i++) v[i] = b.getFloat();
        return v;
    }

    /** Keep the first {@code dim} values (Matryoshka) and rescale to unit length; otherwise cosine on the truncated vector is off. */
    public static float[] truncateNormalize(List<Double> v, int dim) {
        if (v.size() < dim) throw new IllegalStateException("embedding has " + v.size() + " dims, need " + dim);
        double sum = 0;
        for (int i = 0; i < dim; i++) sum += v.get(i) * v.get(i);
        double norm = Math.sqrt(sum);
        float[] out = new float[dim];
        for (int i = 0; i < dim; i++) out[i] = (float) (v.get(i) / norm);
        return out;
    }
}
