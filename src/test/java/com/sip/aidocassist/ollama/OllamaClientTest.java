package com.sip.aidocassist.ollama;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OllamaClientTest {

    @Test
    void truncatesAndRenormalizes() {
        float[] v = OllamaClient.truncateNormalize(List.of(3.0, 4.0, 100.0), 2);
        assertArrayEquals(new float[]{0.6f, 0.8f}, v, 1e-6f);
        assertThrows(IllegalStateException.class, () -> OllamaClient.truncateNormalize(List.of(1.0), 2));
    }

    /** One real call to SIP's Ollama: 4096 dims in, 2000 unit-norm dims out. Run with OLLAMA_URL set. */
    @Test
    @EnabledIfEnvironmentVariable(named = "OLLAMA_URL", matches = ".+")
    void realEmbedding() {
        var client = new OllamaClient(System.getenv("OLLAMA_URL"), "qwen3-embedding:8b");
        String text = "Settlement file cut-off time for real-time payments";
        assertEquals(4096, client.embedRaw(List.of(text)).get(0).size());
        float[] v = client.embed(List.of(text))[0];
        assertEquals(2000, v.length);
        double sum = 0;
        for (float x : v) sum += x * x;
        assertEquals(1.0, Math.sqrt(sum), 1e-4);
        System.out.printf("raw 4096 -> %d dims, norm %.6f%n", v.length, Math.sqrt(sum));
    }
}
