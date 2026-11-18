package com.sip.aidocassist.retrieval;

import com.sip.aidocassist.AiDocAssistApplicationAccess;
import com.sip.aidocassist.db.Migrator;
import com.sip.aidocassist.llm.Embedder;
import com.sip.aidocassist.ollama.OllamaClient;
import com.sip.aidocassist.retrieval.Visible.Ranked;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 on a throwaway database: the group filter inside every retriever (and disabled entries
 * hidden), RRF with SKB's worked example, dedupe, the per-document cap, top 8, and SKB's rare-token
 * self-checks. Ollama is a stand-in that returns a chosen query vector.
 */
class RetrievalTest {

    /** Returns {@code next} as the query embedding and counts calls. */
    static class FakeOllama extends OllamaClient {
        float[] next = axis(0);
        int calls;
        FakeOllama() { super("http://unused", "unused"); }
        @Override public float[][] embed(List<String> texts) {
            calls++;
            assertTrue(texts.get(0).startsWith(QUERY_INSTRUCTION), "questions are embedded with the query instruction");
            return new float[][]{next};
        }
    }

    /** The tag of the stand-in's records: rows inserted without embed_model count as the configured Ollama model. */
    static final String OLLAMA = "ollama/unused";

    /** A unit vector along one axis: records on the same axis are a perfect match, others orthogonal. */
    static float[] axis(int i) {
        float[] v = new float[OllamaClient.DIM];
        v[i] = 1f;
        return v;
    }

    @TempDir Path tmp;
    HikariDataSource ds;
    JdbcClient db;
    FakeOllama ollama;
    Fusion fusion;

    @BeforeEach
    void setUp() throws Exception {
        ds = (HikariDataSource) AiDocAssistApplicationAccess.dataSource(tmp.resolve("r.db").toString());
        Migrator.migrate(ds);
        db = JdbcClient.create(ds);
        ollama = new FakeOllama();
        fusion = new Fusion(db, new Embedder(ollama, "unused"), new VectorRetriever(db), new FullTextRetriever(db), new RareTokenRetriever(db));
        db.sql("""
                INSERT INTO sync_sources (id, source, name, config, default_group_ids, enabled) VALUES
                  ('gen', 'filesystem', 'General', '{}', '["general"]', 1),
                  ('fin', 'filesystem', 'Finance', '{}', '["finance"]', 1),
                  ('off', 'filesystem', 'Disabled', '{}', '["general"]', 0)""").update();
        record("r1", "gen", "docA.md", 0, "Runbook > Cut-off", "Payments are batched at 18:00 every day.", axis(0));
        record("r2", "gen", "docB.md", 0, "Spec > PicTrust", "PicTrust is String(1) and may be left empty.", axis(1));
        record("r3", "fin", "docC.md", 0, "Finance > Limits", "PicTrust approval limit for finance only.", axis(1));
        record("r4", "off", "docD.md", 0, "Old > PicTrust", "PicTrust notes from a disabled entry.", axis(1));
        for (int i = 0; i < 5; i++) // one long document that mentions settlement everywhere
            record("e" + i, "gen", "docE.md", i, "Settlement guide > Part " + i, "Settlement happens nightly, part " + i + ".", axis(2));
        for (int i = 0; i < 12; i++) // ordinary chunks, so a word in a few chunks counts as rare
            record("f" + i, "gen", "filler" + i + ".md", 0, "Meeting notes " + i, "Weekly meeting notes about staffing and rooms " + i + ".", axis(3));
    }

    @AfterEach
    void tearDown() { ds.close(); }

    private void record(String id, String entry, String file, int n, String title, String content, float[] embedding) {
        db.sql("""
                INSERT INTO knowledge_records (id, source, source_id, parent_id, source_type, title, content, raw_content, updated_at,
                    allowed_group_ids, sync_source_id, content_checksum, embedding)
                VALUES (?, 'filesystem', ?, ?, 'doc-section', ?, ?, ?, '2026-10-13T00:00:00.000Z',
                    (SELECT default_group_ids FROM sync_sources WHERE id = ?), ?, 'x', ?)""")
                .params(id, "C:/docs/" + file + "#" + n, "C:/docs/" + file, title, content, content, entry, entry, OllamaClient.toBytes(embedding))
                .update();
    }

    private static List<String> ids(List<Ranked> r) { return r.stream().map(Ranked::id).toList(); }

    // ---------- the access filter, inside every retriever ----------

    @Test
    void everyRetrieverOnlySeesTheUsersGroupsAndEnabledEntries() {
        List<String> general = List.of("general"), finance = List.of("finance");
        var vector = new VectorRetriever(db);
        var fulltext = new FullTextRetriever(db);
        var rare = new RareTokenRetriever(db);
        // r2, r3, r4 all match "PicTrust" in meaning, words and token; only the visible one may come back.
        assertEquals(List.of("r2"), ids(vector.search(axis(1), OLLAMA, OLLAMA, general, 20)).subList(0, 1));
        assertFalse(ids(vector.search(axis(1), OLLAMA, OLLAMA, general, 20)).contains("r3"));
        assertFalse(ids(vector.search(axis(1), OLLAMA, OLLAMA, general, 20)).contains("r4"), "a disabled entry's records are never searched");
        assertEquals(List.of("r2"), ids(fulltext.search("PicTrust", general, 20)));
        assertEquals(List.of("r3"), ids(fulltext.search("PicTrust", finance, 20)));
        assertEquals(List.of("r2"), ids(rare.search("PicTrust", general, rare.pick("PicTrust", general), 20)));
        assertEquals(List.of("r3"), ids(rare.search("PicTrust", finance, rare.pick("PicTrust", finance), 20)));

        ollama.next = axis(1);
        List<String> seenByGeneral = fusion.search("Can we pass an empty value for PicTrust?", general).hits().stream().map(Fusion.Hit::id).toList();
        assertEquals("r2", seenByGeneral.get(0));
        assertFalse(seenByGeneral.contains("r3") || seenByGeneral.contains("r4"));
        assertEquals("r3", fusion.search("PicTrust", finance).hits().get(0).id());
        // Both groups: both records, never the disabled one.
        assertTrue(fusion.search("PicTrust", List.of("general", "finance")).hits().stream().map(Fusion.Hit::id).toList().containsAll(List.of("r2", "r3")));
    }

    @Test
    void noGroupsSeesNothingAndQueriesNothing() {
        assertTrue(fusion.search("PicTrust", List.of()).hits().isEmpty());
        assertTrue(fusion.search("   ", List.of("general")).hits().isEmpty());
        assertEquals(0, ollama.calls, "no groups or no question: not even the embedding call");
    }

    // ---------- fusion ----------

    @Test
    void rrfFoundBySeveralBeatsFirstInOne() {
        // SKB docs/retrieval.md's example: A ranks (2,1,1), C (3,2,-), D (-,3,2), B (1,-,-).
        record("A", "gen", "a.md", 0, "A", "a", axis(5));
        record("B", "gen", "b.md", 0, "B", "b", axis(5));
        record("C", "gen", "c.md", 0, "C", "c", axis(5));
        record("D", "gen", "d.md", 0, "D", "d", axis(5));
        Map<String, List<Ranked>> lists = new LinkedHashMap<>();
        lists.put("vector", List.of(new Ranked("B", 0), new Ranked("A", 0), new Ranked("C", 0)));
        lists.put("fulltext", List.of(new Ranked("A", 0), new Ranked("C", 0), new Ranked("D", 0)));
        lists.put("raretoken", List.of(new Ranked("A", 0), new Ranked("D", 0)));
        var hits = fusion.fuse(lists);
        assertEquals(List.of("A", "C", "D", "B"), hits.stream().map(Fusion.Hit::id).toList(), "C and D tie at 0.0320; id breaks the tie");
        assertEquals(1.0 / 62 + 1.0 / 61 + 1.0 / 61, hits.get(0).score(), 1e-12);
        assertEquals(0.0489, hits.get(0).score(), 1e-4);
        assertEquals(1.0 / 61, hits.get(3).score(), 1e-12);
        assertEquals(Map.of("vector", 2, "fulltext", 1, "raretoken", 1), hits.get(0).ranks());
        assertEquals("A", hits.get(0).title(), "title, source and dates come from knowledge_records");
    }

    @Test
    void oneDocumentGetsAtMostThreePlacesAndAtMostEightOverall() {
        ollama.next = axis(2);
        var hits = fusion.search("settlement nightly", List.of("general")).hits();
        assertEquals(3, hits.stream().filter(h -> h.file().equals("docE.md")).count(), "docE has 5 matching chunks; 3 are kept");
        ollama.next = axis(3);
        var meetings = fusion.search("weekly meeting notes staffing rooms", List.of("general")).hits();
        assertEquals(Fusion.FINAL_K, meetings.size(), "12 matching chunks from 12 files: top 8 only");
        assertEquals(meetings.size(), meetings.stream().map(Fusion.Hit::sourceId).distinct().count(), "no duplicates");
    }

    @Test
    void eachEmbeddingModelIsSearchedInItsOwnSpace() {
        // Two records on the same axis, one embedded by Gemini: a query vector is only compared with its own model's records.
        record("G", "gen", "g.md", 0, "Gemini doc", "gamma", axis(7));
        record("O", "gen", "o.md", 0, "Ollama doc", "omega", axis(7));
        db.sql("UPDATE knowledge_records SET embed_model = 'gemini/m' WHERE id = 'G'").update();
        var vector = new VectorRetriever(db);
        assertEquals(List.of("gemini/m", OLLAMA), vector.models(OLLAMA, List.of("general")));
        assertEquals(List.of("G"), ids(vector.search(axis(7), "gemini/m", OLLAMA, List.of("general"), 20)));
        assertFalse(ids(vector.search(axis(7), OLLAMA, OLLAMA, List.of("general"), 20)).contains("G"));

        List<String> asked = new ArrayList<>();
        var both = new Fusion(db, new Embedder(ollama, "unused") {
            @Override public float[] embedQuery(String q, String model) { asked.add(model); return axis(7); }
        }, vector, new FullTextRetriever(db), new RareTokenRetriever(db));
        var hits = both.search("anything", List.of("general")).hits();
        assertEquals(List.of("gemini/m", OLLAMA), asked, "the question is embedded once per model");
        assertEquals(Set.of("G", "O"), Set.copyOf(hits.stream().map(Fusion.Hit::id).toList().subList(0, 2)), "each first in its own list");
        assertEquals(Map.of("vector", 1), hits.stream().filter(h -> h.id().equals("G")).findFirst().orElseThrow().ranks());

        // Gemini down for the question: its records lose their vector vote, the Ollama ones still have it.
        var geminiDown = new Fusion(db, new Embedder(ollama, "unused") {
            @Override public float[] embedQuery(String q, String model) {
                if (model.startsWith("gemini/")) throw new org.springframework.web.client.ResourceAccessException("down");
                return axis(7);
            }
        }, vector, new FullTextRetriever(db), new RareTokenRetriever(db));
        List<String> down = geminiDown.search("anything", List.of("general")).hits().stream().map(Fusion.Hit::id).toList();
        assertEquals("O", down.get(0));
        assertFalse(down.contains("G"));
        // Every model down: the search fails, as before (the chat says the model server can't be reached).
        var allDown = new Fusion(db, new Embedder(ollama, "unused") {
            @Override public float[] embedQuery(String q, String model) { throw new org.springframework.web.client.ResourceAccessException("down"); }
        }, vector, new FullTextRetriever(db), new RareTokenRetriever(db));
        assertThrows(org.springframework.web.client.ResourceAccessException.class, () -> allDown.search("anything", List.of("general")));
    }

    // ---------- the retrievers' own rules ----------

    @Test
    void rareTokenSelfChecksFromSkb() {
        assertEquals(List.of("pass", "empty", "value", "pictrust"), RareTokenRetriever.tokens("Thanks! Can we pass an empty value for PicTrust?"));
        // identifiers survive intact; version numbers keep their dots; short bare numbers are dropped
        assertEquals(List.of("connected_party", "v2.2", "section", "3.8.1", "row"),
                RareTokenRetriever.tokens("What's CONNECTED_PARTY in v2.2, section 3.8.1, row 35?"));
        // 4+ digit numbers are kept: an error code or ticket id asked without its prefix
        assertEquals(List.of("01722", "fixed", "ticket", "7285", "row"), RareTokenRetriever.tokens("How was 01722 fixed in ticket 7285, row 100?"));
        assertEquals("%CONNECTED\\_PARTY%", RareTokenRetriever.like("CONNECTED_PARTY"));
        assertEquals("%100\\%%", RareTokenRetriever.like("100%"));
        // A word in more than 25% of the visible chunks isn't rare: "notes" is in 12 of 19.
        var picked = new RareTokenRetriever(db).pick("meeting notes about PicTrust", List.of("general"));
        assertEquals("pictrust", picked.get(0).token(), "the rarest first");
        assertFalse(picked.stream().anyMatch(p -> p.token().equals("notes") || p.token().equals("meeting")));
    }

    @Test
    void fullTextIgnoresSearchSyntaxInTheQuestion() {
        assertEquals("\"drop\" OR \"near\" OR \"x\" OR \"pictrust\"", FullTextRetriever.matchExpression("He said \"drop\" -- NEAR(x) OR * PicTrust?"));
        assertNull(FullTextRetriever.matchExpression("is it the?"));
        var fulltext = new FullTextRetriever(db);
        for (String q : List.of("\"unbalanced", "AND OR NOT", "pictrust*", "title:secret", "a - b ^ c", "NEAR(PicTrust empty, 2)"))
            assertDoesNotThrow(() -> fulltext.search(q, List.of("general"), 20), q);
        assertEquals("r2", ids(fulltext.search("Is PicTrust allowed to be empty?", List.of("general"), 20)).get(0), "porter: empty ~ empty, OR-ed words");
    }
}
