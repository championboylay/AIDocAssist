package com.sip.aidocassist.llm;

import com.sip.aidocassist.ollama.OllamaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Embeddings with a fallback (owner's decision 2026-10-24): Google AI Studio's Gemini model with
 * the first key, then the second, then Ollama. Gemini and Ollama vectors live in different spaces
 * and can't be compared, so every vector is stored with the model that made it (embed_model) and a
 * query is embedded once per model present, each compared only with its own records (Fusion).
 *
 * Each key has a {@link Quota} kept below Google's limits, so a large document waits for the next
 * minute instead of spilling into Ollama vectors; a 429/503 is retried on the same key with backoff.
 */
@Component
public class Embedder {

    private static final Logger log = LoggerFactory.getLogger(Embedder.class);
    static final String GEMINI = "gemini/";
    static final String OLLAMA = "ollama/";
    // gemini-embedding-2 takes no task type; the task goes in the text (Google's embeddings guide).
    static final String GEMINI_QUERY = "task: question answering | query: ";
    static final String GEMINI_DOC = "title: none | text: "; // the heading path is already inside the text
    static final int MAX_RETRIES = 3;
    /** Google resets daily quotas at midnight Pacific time. */
    static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");
    private static final Pattern RETRY_DELAY = Pattern.compile("\"retryDelay\":\\s*\"(\\d+)(?:\\.\\d+)?s\"");

    /** Vectors (unit length, {@link OllamaClient#DIM} values) and the tag of the model that made them. */
    public record Embedded(String model, float[][] vectors) {}

    private final OllamaClient ollama;
    private final String ollamaTag;
    private final String geminiModel;
    private final List<String> keys;
    private final List<Quota> quotas;
    private final RestClient gemini;

    @Autowired
    public Embedder(OllamaClient ollama, @Value("${embed.model}") String ollamaModel,
                    @Value("${gemini.embed-model}") String geminiModel, @Value("${gemini.api-keys}") String keys,
                    @Value("${gemini.rpm}") int rpm, @Value("${gemini.tpm}") int tpm, @Value("${gemini.rpd}") int rpd) {
        this.ollama = ollama;
        this.ollamaTag = OLLAMA + ollamaModel;
        this.geminiModel = geminiModel;
        this.keys = Arrays.stream(keys.split(",")).map(String::strip).filter(k -> !k.isEmpty()).toList();
        this.quotas = java.util.stream.IntStream.range(0, this.keys.size()).mapToObj(i -> new Quota(i + 1, rpm, tpm, rpd)).toList();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofMinutes(2));
        this.gemini = RestClient.builder().baseUrl("https://generativelanguage.googleapis.com/v1beta").requestFactory(factory).build();
    }

    /** The default budgets (80 RPM, 25k TPM, 950 RPD: below the free tier's 100 / 30k / 1000). */
    public Embedder(OllamaClient ollama, String ollamaModel, String geminiModel, String keys) {
        this(ollama, ollamaModel, geminiModel, keys, 80, 25_000, 950);
    }

    /** Ollama only (tests, or no Gemini keys configured). */
    public Embedder(OllamaClient ollama, String ollamaModel) {
        this(ollama, ollamaModel, "unused", "");
    }

    /** The tag a sync tries first; records embedded by another model are re-embedded when their file is re-read. */
    public String preferred() {
        return keys.isEmpty() ? ollamaTag : GEMINI + geminiModel;
    }

    /** The tag of records stored before embed_model existed (all embedded by the configured Ollama model). */
    public String legacy() {
        return ollamaTag;
    }

    public String geminiModel() {
        return geminiModel;
    }

    /** Each key's use as this app counts it, for the admin Quota page. Keys are shown by their last 4 characters only. */
    public List<Quota.Usage> usage() {
        return java.util.stream.IntStream.range(0, keys.size()).mapToObj(i -> quotas.get(i).usage(i + 1, mask(keys.get(i)))).toList();
    }

    /** Document embeddings: Gemini key 1, key 2, then Ollama. Throws only when all of them fail. */
    public Embedded embed(List<String> texts) {
        for (int i = 0; i < keys.size(); i++) {
            try {
                float[][] v = call(i, geminiModel, texts.stream().map(t -> GEMINI_DOC + t).toList());
                if (v != null) return new Embedded(GEMINI + geminiModel, v);
                log.info("gemini key {} of {}: today's budget is used up", i + 1, keys.size());
            } catch (RuntimeException e) { // bad key, outage, retries used up: try the next one
                log.warn("gemini embedding with key {} of {} failed: {}", i + 1, keys.size(), cut(e.getMessage()));
            }
        }
        return new Embedded(ollamaTag, ollama.embed(texts));
    }

    /** A question's embedding in the space of {@code model} (a tag from {@link #embed}): Gemini keys in order, or Ollama. */
    public float[] embedQuery(String question, String model) {
        if (model.equals(ollamaTag)) return ollama.embedQuery(question);
        if (!model.startsWith(GEMINI) || keys.isEmpty())
            throw new IllegalStateException("no client for embeddings made by " + model + "; Re-read all to re-embed them");
        RuntimeException last = null;
        for (int i = 0; i < keys.size(); i++) {
            try {
                float[][] v = call(i, model.substring(GEMINI.length()), List.of(GEMINI_QUERY + question));
                if (v != null) return v[0];
                last = new IllegalStateException("gemini key " + (i + 1) + ": today's budget is used up");
            } catch (RuntimeException e) {
                log.warn("gemini query embedding with key {} of {} failed: {}", i + 1, keys.size(), cut(e.getMessage()));
                last = e;
            }
        }
        throw last;
    }

    /** One key: wait for its budget, retry 429/503 with backoff. Null when the key's day is spent. */
    private float[][] call(int i, String model, List<String> texts) {
        Quota q = quotas.get(i);
        try {
            for (int attempt = 0; ; attempt++) {
                if (!q.acquire(texts.size(), tokens(texts))) return null; // a retry is a request too
                try {
                    return gemini(model, keys.get(i), texts);
                } catch (HttpStatusCodeException e) {
                    int s = e.getStatusCode().value();
                    q.refused(s + " " + cut(e.getResponseBodyAsString()));
                    if ((s != 429 && s != 503) || attempt == MAX_RETRIES) throw e;
                    long ms = Math.min(60_000, retryAfter(e, 1000L << attempt)) + ThreadLocalRandom.current().nextLong(500);
                    log.info("gemini key {}: {}, retry {} of {} in {} ms", i + 1, s, attempt + 1, MAX_RETRIES, ms);
                    sleep(ms);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the Gemini quota", e);
        }
    }

    /** Package-private so tests don't wait. */
    void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    /** Retry-After (seconds), or the retryDelay Google puts in the error body ("33s"), else the backoff. */
    static long retryAfter(HttpStatusCodeException e, long backoff) {
        String h = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        if (h != null && h.strip().matches("\\d+")) return Long.parseLong(h.strip()) * 1000;
        var m = RETRY_DELAY.matcher(e.getResponseBodyAsString());
        return m.find() ? Long.parseLong(m.group(1)) * 1000 : backoff;
    }

    /** About 4 characters per English token (CLAUDE.md: English only); 3 keeps a margin. */
    static int tokens(List<String> texts) {
        return texts.stream().mapToInt(t -> t.length() / 3 + 1).sum();
    }

    static String mask(String key) {
        return "…" + key.substring(Math.max(0, key.length() - 4));
    }

    /**
     * One key's budget: requests and estimated tokens over a sliding minute, and requests per day.
     * Shared by the sync thread and chat questions. Every text counts as one request (Google may
     * count batch items one by one).
     * ponytail: in memory, so the day's count restarts with the app (Google still enforces its own);
     * store it in SQLite if restarts during heavy syncs become common.
     */
    public static final class Quota {

        /** What the Quota page shows for one key. */
        public record Usage(int index, String key, int minuteRequests, int rpm, int minuteTokens, int tpm,
                            int today, int rpd, Instant resetsAt, Instant lastRefusalAt, String lastRefusal) {}

        final int key, rpm, tpm, rpd;
        private final ArrayDeque<long[]> window = new ArrayDeque<>(); // {millis, tokens, requests} of the last 60 s
        private LocalDate day;
        private int usedToday;
        private Instant lastRefusalAt;
        private String lastRefusal;

        Quota(int key, int rpm, int tpm, int rpd) {
            this.key = key;
            this.rpm = rpm;
            this.tpm = tpm;
            this.rpd = rpd;
        }

        /** Waits until the call fits this minute's budget; false when today's budget is spent. */
        synchronized boolean acquire(int requests, int tokens) throws InterruptedException {
            if (requests > rpm || tokens > tpm)
                throw new IllegalArgumentException("one batch (" + requests + " texts, ~" + tokens + " tokens) exceeds the per-minute budget");
            rollDay();
            if (usedToday + requests > rpd) return false;
            while (true) {
                long now = System.currentTimeMillis();
                expire(now);
                long r = 0, t = 0;
                for (long[] w : window) { t += w[1]; r += w[2]; }
                if (r + requests <= rpm && t + tokens <= tpm) break;
                long ms = Math.max(50, window.peekFirst()[0] + 60_000 - now);
                if (ms >= 1000) log.info("gemini key {}: minute budget full ({}/{} requests, ~{}/{} tokens), waiting {}s",
                        key, r, rpm, t, tpm, ms / 1000);
                wait(ms); // releases the lock while waiting
            }
            window.addLast(new long[]{System.currentTimeMillis(), tokens, requests});
            usedToday += requests;
            return true;
        }

        synchronized void refused(String why) {
            lastRefusalAt = Instant.now();
            lastRefusal = why;
        }

        synchronized Usage usage(int index, String key) {
            rollDay();
            expire(System.currentTimeMillis());
            int r = 0, t = 0;
            for (long[] w : window) { t += (int) w[1]; r += (int) w[2]; }
            Instant reset = day.plusDays(1).atStartOfDay(QUOTA_ZONE).toInstant();
            return new Usage(index, key, r, rpm, t, tpm, usedToday, rpd, reset, lastRefusalAt, lastRefusal);
        }

        private void rollDay() {
            LocalDate today = LocalDate.now(QUOTA_ZONE);
            if (!today.equals(day)) {
                day = today;
                usedToday = 0;
            }
        }

        private void expire(long now) {
            while (!window.isEmpty() && window.peekFirst()[0] <= now - 60_000) window.pollFirst();
        }
    }

    private record Values(List<Double> values) {}

    private record BatchResponse(List<Values> embeddings) {}

    /** One batch call with one key; package-private so tests can stand in for Google. */
    float[][] gemini(String model, String key, List<String> texts) {
        var requests = texts.stream().map(t -> Map.of("model", "models/" + model, "content", Map.of("parts", List.of(Map.of("text", t))),
                "outputDimensionality", OllamaClient.DIM)).toList();
        // The key goes in a header, not the URL, so it never shows up in an error message or log.
        BatchResponse r = gemini.post().uri("/models/{model}:batchEmbedContents", model).header("x-goog-api-key", key)
                .body(Map.of("requests", requests)).retrieve().body(BatchResponse.class);
        if (r == null || r.embeddings() == null || r.embeddings().size() != texts.size())
            throw new IllegalStateException("gemini returned " + (r == null || r.embeddings() == null ? 0 : r.embeddings().size())
                    + " embeddings for " + texts.size() + " texts");
        float[][] out = new float[texts.size()][];
        for (int i = 0; i < out.length; i++) out[i] = OllamaClient.truncateNormalize(r.embeddings().get(i).values(), OllamaClient.DIM);
        return out;
    }

    static String cut(String s) {
        return s == null || s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
