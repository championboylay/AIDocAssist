package com.sip.aidocassist.llm;

import com.sip.aidocassist.ollama.OllamaClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The fallback order: Gemini key 1, key 2, then Ollama; each vector tagged with the model that made it. */
class EmbedderTest {

    static float[] unit(int axis) {
        float[] v = new float[OllamaClient.DIM];
        v[axis] = 1f;
        return v;
    }

    static class FakeOllama extends OllamaClient {
        boolean down;
        final List<String> embedded = new ArrayList<>();
        FakeOllama() { super("http://unused", "qwen"); }
        @Override public float[][] embed(List<String> texts) {
            if (down) throw new ResourceAccessException("connection refused");
            embedded.addAll(texts);
            return texts.stream().map(t -> unit(0)).toArray(float[][]::new);
        }
    }

    /** Google stand-in: the keys in {@code failing} answer 429; records which key was tried with what. */
    static class FakeGemini extends Embedder {
        final Set<String> failing;
        final List<String> calls = new ArrayList<>();
        final List<Long> slept = new ArrayList<>();
        FakeGemini(OllamaClient ollama, String keys, Set<String> failing) {
            this(ollama, keys, failing, 950);
        }
        FakeGemini(OllamaClient ollama, String keys, Set<String> failing, int rpd) {
            super(ollama, "qwen", "gem", keys, 80, 25_000, rpd);
            this.failing = failing;
        }
        @Override void sleep(long ms) { slept.add(ms); }
        @Override float[][] gemini(String model, String key, List<String> texts) {
            calls.add(key + " " + model + " " + texts.get(0));
            if (failing.contains(key)) throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "quota", null, null, null);
            return texts.stream().map(t -> unit(1)).toArray(float[][]::new);
        }
    }

    @Test
    void firstKeyThenSecondThenOllama() {
        var ollama = new FakeOllama();
        var ok = new FakeGemini(ollama, "k1, k2", Set.of());
        assertEquals("gemini/gem", ok.preferred());
        var r = ok.embed(List.of("Doc text"));
        assertEquals("gemini/gem", r.model());
        assertEquals(List.of("k1 gem title: none | text: Doc text"), ok.calls, "documents carry gemini-embedding-2's document prefix");

        var firstLimited = new FakeGemini(ollama, "k1,k2", Set.of("k1"));
        assertEquals("gemini/gem", firstLimited.embed(List.of("x")).model());
        assertEquals(List.of("k1", "k1", "k1", "k1", "k2"), firstLimited.calls.stream().map(c -> c.split(" ")[0]).toList(),
                "a 429 is retried 3 times on the same key before the next key");
        assertEquals(3, firstLimited.slept.size());
        for (int i = 0; i < 3; i++) {
            long base = 1000L << i; // 1 s, 2 s, 4 s, plus up to 0.5 s of jitter
            assertTrue(firstLimited.slept.get(i) >= base && firstLimited.slept.get(i) < base + 500, "backoff " + firstLimited.slept);
        }
        assertEquals(4, firstLimited.usage().get(0).today(), "every attempt counts against the key's day");
        assertNotNull(firstLimited.usage().get(0).lastRefusal());

        var bothLimited = new FakeGemini(ollama, "k1,k2", Set.of("k1", "k2"));
        var fallback = bothLimited.embed(List.of("y"));
        assertEquals("ollama/qwen", fallback.model());
        assertEquals(List.of("y"), ollama.embedded, "Ollama gets the plain text, no Gemini prefix");

        ollama.down = true;
        assertThrows(ResourceAccessException.class, () -> bothLimited.embed(List.of("z")), "all three down: the sync fails, as before");

        var noKeys = new Embedder(ollama, "qwen");
        assertEquals("ollama/qwen", noKeys.preferred());
    }

    @Test
    void aQuestionIsEmbeddedByTheModelOfTheRecordsItIsComparedWith() {
        var ollama = new FakeOllama();
        var e = new FakeGemini(ollama, "k1,k2", Set.of("k1"));
        assertArrayEquals(unit(1), e.embedQuery("why?", "gemini/gem"));
        assertEquals("k2 gem task: question answering | query: why?", e.calls.get(e.calls.size() - 1));
        assertArrayEquals(unit(0), e.embedQuery("why?", "ollama/qwen"));
        assertTrue(ollama.embedded.get(0).startsWith(OllamaClient.QUERY_INSTRUCTION), "Ollama keeps its own query instruction");
        // Never a Gemini question vector from Ollama, or from a model no longer configured.
        var both = new FakeGemini(ollama, "k1,k2", Set.of("k1", "k2"));
        assertThrows(HttpClientErrorException.class, () -> both.embedQuery("why?", "gemini/gem"));
        assertThrows(IllegalStateException.class, () -> e.embedQuery("why?", "ollama/other-model"));
        assertThrows(IllegalStateException.class, () -> new Embedder(ollama, "qwen").embedQuery("why?", "gemini/gem"));
    }

    @Test
    void theBudgetHoldsBackAndADaySpentMovesOn() throws Exception {
        var q = new Embedder.Quota(1, 3, 100, 5);
        assertTrue(q.acquire(2, 40));
        assertTrue(q.acquire(1, 40));
        long t0 = System.currentTimeMillis();
        Thread waiter = new Thread(() -> { try { q.acquire(1, 10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        waiter.start();
        waiter.join(500);
        assertTrue(waiter.isAlive(), "a 4th request in the same minute waits (3 RPM)");
        waiter.interrupt();
        waiter.join();
        assertTrue(System.currentTimeMillis() - t0 < 5_000);
        assertThrows(IllegalArgumentException.class, () -> q.acquire(1, 101), "a batch bigger than the minute's budget can never fit");

        var day = new Embedder.Quota(1, 100, 100_000, 5);
        assertTrue(day.acquire(5, 1));
        assertFalse(day.acquire(1, 1), "the day's budget is spent: no wait, the caller moves on");
        var u = day.usage(1, "…abcd");
        assertEquals(List.of(5, 5, 5), List.of(u.today(), u.rpd(), u.minuteRequests()));
        assertTrue(u.resetsAt().isAfter(java.time.Instant.now()));

        // Both keys' days spent: straight to Ollama, nothing sent to Google.
        var ollama = new FakeOllama();
        var spent = new FakeGemini(ollama, "k1,k2", Set.of(), 1);
        assertEquals("gemini/gem", spent.embed(List.of("a")).model());
        assertEquals("gemini/gem", spent.embed(List.of("b")).model(), "key 1's day spent: key 2");
        assertEquals("ollama/qwen", spent.embed(List.of("c")).model(), "both spent: Ollama");
        assertEquals(List.of("k1", "k2"), spent.calls.stream().map(c -> c.split(" ")[0]).toList(), "a spent key is never called");
        assertThrows(IllegalStateException.class, () -> spent.embedQuery("why?", "gemini/gem"), "no Gemini question vector left today");
        assertEquals(List.of(1, 1), List.of(Embedder.tokens(List.of("")), Embedder.tokens(List.of("ab"))));
        assertEquals(3_334, Embedder.tokens(List.of("x".repeat(10_000))), "about 3 characters per token");
    }

    @Test
    void retryWaitsAsLongAsGoogleAsks() {
        var headers = new HttpHeaders();
        headers.add("Retry-After", "7");
        var withHeader = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "quota", headers, new byte[0], StandardCharsets.UTF_8);
        assertEquals(7_000, Embedder.retryAfter(withHeader, 1_000));
        byte[] body = "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\": \"33s\"}]}}"
                .getBytes(StandardCharsets.UTF_8);
        var withBody = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "quota", new HttpHeaders(), body, StandardCharsets.UTF_8);
        assertEquals(33_000, Embedder.retryAfter(withBody, 1_000));
        var plain = HttpClientErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "busy", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
        assertEquals(4_000, Embedder.retryAfter(plain, 4_000), "nothing from Google: the exponential backoff");
    }

    /** One real call per configured key: 2000 unit-norm dims. Run with GEMINI_API_KEYS set. */
    @Test
    @EnabledIfEnvironmentVariable(named = "GEMINI_API_KEYS", matches = ".+")
    void realGemini() {
        String model = System.getenv().getOrDefault("GEMINI_EMBED_MODEL", "gemini-embedding-2");
        for (String key : System.getenv("GEMINI_API_KEYS").split(",")) {
            float[] v = new Embedder(new FakeOllama(), "qwen", model, key.strip()).embedQuery("settlement cut-off time", "gemini/" + model);
            double sum = 0;
            for (float x : v) sum += x * x;
            assertEquals(OllamaClient.DIM, v.length);
            assertEquals(1.0, Math.sqrt(sum), 1e-4);
        }
    }
}
