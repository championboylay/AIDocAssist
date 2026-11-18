package com.sip.aidocassist.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sip.aidocassist.AiDocAssistApplicationAccess;
import com.sip.aidocassist.db.Migrator;
import com.sip.aidocassist.llm.Embedder;
import com.sip.aidocassist.ollama.OllamaClient;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The re-sync on a throwaway database and real files: SKB docs/ingestion.md's worked example
 * ("three syncs of one folder") plus Stop, Re-read, model errors and the safety rules. Ollama is
 * replaced by a stand-in that returns unit vectors and records what it was asked to embed.
 */
class SyncServiceTest {

    /** Fixed unit vectors; records every text; can fail like an Ollama server error, or run a hook. */
    static class FakeOllama extends OllamaClient {
        final List<String> embedded = new ArrayList<>();
        boolean failOnBoom = true;
        Runnable onEmbed = () -> {};

        FakeOllama() { super("http://unused", "unused"); }

        @Override
        public float[][] embed(List<String> texts) {
            onEmbed.run();
            if (failOnBoom && texts.stream().anyMatch(t -> t.contains("BOOM")))
                throw HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "error", null,
                        "CUDA error: illegal memory access".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            embedded.addAll(texts);
            float[][] out = new float[texts.size()][OllamaClient.DIM];
            for (float[] v : out) v[0] = 1f;
            return out;
        }
    }

    @TempDir Path tmp;
    HikariDataSource ds;
    JdbcClient db;
    FakeOllama ollama;
    SyncService sync;
    Path folder;
    String entry;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        ds = (HikariDataSource) AiDocAssistApplicationAccess.dataSource(tmp.resolve("sync.db").toString());
        Migrator.migrate(ds);
        db = JdbcClient.create(ds);
        ollama = new FakeOllama();
        sync = new SyncService(db, new TransactionTemplate(new DataSourceTransactionManager(ds)), new Embedder(ollama, "unused"), json,
                new com.sip.aidocassist.sources.UploadStore(tmp.resolve("uploads").toString()));
        folder = Files.createDirectories(tmp.resolve("docs"));
        entry = UUID.randomUUID().toString();
        db.sql("INSERT INTO sync_sources (id, source, name, config, default_group_ids) VALUES (?, 'filesystem', 'Docs', ?, '[\"general\"]')")
                .params(entry, json.writeValueAsString(Map.of("folder_path", folder.toString()))).update();
    }

    @AfterEach
    void tearDown() { ds.close(); }

    private Path write(String name, String text, int secondsAgo) throws Exception {
        Path f = Files.writeString(folder.resolve(name), text);
        Files.setLastModifiedTime(f, FileTime.from(Instant.now().minusSeconds(secondsAgo)));
        return f;
    }

    private Map<String, Object> run(String runId) {
        return db.sql("SELECT * FROM sync_runs WHERE id = ?").param(runId).query().singleRow();
    }

    private List<String> items(String runId) {
        return db.sql("SELECT parent_id, action, chunk_count FROM sync_run_items WHERE sync_run_id = ? ORDER BY parent_id")
                .param(runId).query((rs, i) -> rs.getString(1).replaceAll(".*/", "") + " " + rs.getString(2) + " " + rs.getInt(3)).list();
    }

    private int count(String sql, Object... args) {
        return db.sql(sql).params(List.of(args)).query(Integer.class).single();
    }

    @Test
    void threeSyncsOfOneFolder() throws Exception {
        write("Spec.md", "# Spec\n\n## Auth\n\nToken flow.\n\n## Errors\n\nE1001 means both given.\n\n## Limits\n\n100 calls per minute.\n", 100);
        write("Runbook.md", "## Restart\n\nStop then start.\n", 100);
        write("Keys.md", "## Keys\n\naws AKIAIOSFODNN7EXAMPLE\n\n## Other\n\nFine text.\n", 100);
        write("old.tmp", "scratch", 100);
        write("shot.png", "not text", 100);

        // Sync 1: everything is new.
        String r1 = sync.run(entry, null, false);
        assertEquals("success", run(r1).get("status"));
        assertEquals(5, ((Number) run(r1).get("records_inserted")).intValue());
        assertEquals(List.of("Keys.md inserted 1", "Runbook.md inserted 1", "Spec.md inserted 3"), items(r1));
        assertEquals(5, ollama.embedded.size());
        assertTrue(ollama.embedded.contains("Spec > Spec > Errors\n\nE1001 means both given."), "title embedded with the text");
        assertEquals(List.of("filename-pattern", "secret-suspected", "unsupported-type"),
                db.sql("SELECT reason FROM ingest_skips ORDER BY reason").query(String.class).list());
        assertTrue(db.sql("SELECT item FROM ingest_skips WHERE reason = 'secret-suspected'").query(String.class).single().endsWith("Keys.md#0"));
        assertEquals(0, count("SELECT count(*) FROM knowledge_records WHERE content LIKE '%AKIA%'"), "a secret is never stored");
        byte[] blob = db.sql("SELECT embedding FROM knowledge_records WHERE title = 'Spec > Spec > Limits'").query(byte[].class).single();
        assertEquals(OllamaClient.DIM * 4, blob.length);
        assertEquals(1f, OllamaClient.fromBytes(blob)[0]);
        assertEquals(1, count("SELECT count(*) FROM knowledge_fts WHERE knowledge_fts MATCH 'minute'"), "full-text index follows");
        assertEquals(5, count("SELECT count(*) FROM knowledge_records WHERE allowed_group_ids = '[\"general\"]' AND sync_source_id = ?", entry));
        assertEquals("ok", db.sql("SELECT last_status FROM sync_sources WHERE id = ?").param(entry).query(String.class).single());

        // Sync 2: one section edited (file newer), Runbook deleted from the folder.
        ollama.embedded.clear();
        write("Spec.md", "# Spec\n\n## Auth\n\nToken flow.\n\n## Errors\n\nE1001 means both uen and name given.\n\n## Limits\n\n100 calls per minute.\n", 0);
        Files.delete(folder.resolve("Runbook.md"));
        String r2 = sync.run(entry, null, false);
        assertEquals(List.of("Spec > Spec > Errors\n\nE1001 means both uen and name given."), ollama.embedded, "only the changed chunk is embedded");
        assertEquals(List.of("Keys.md skipped 1", "Runbook.md deleted 1", "Spec.md updated 3"), items(r2));
        var counts = run(r2);
        assertEquals(List.of(0, 1, 1, 3), List.of(((Number) counts.get("records_inserted")).intValue(), ((Number) counts.get("records_updated")).intValue(),
                ((Number) counts.get("records_deleted")).intValue(), ((Number) counts.get("records_skipped")).intValue()));
        assertEquals(0, count("SELECT count(*) FROM knowledge_records WHERE parent_id LIKE '%Runbook.md'"));

        // Sync 3: no file changed, the entry's tags did: no parse, no embedding, records re-tagged.
        ollama.embedded.clear();
        db.sql("UPDATE sync_sources SET default_group_ids = '[\"general\",\"contracts\"]' WHERE id = ?").param(entry).update();
        String r3 = sync.run(entry, null, false);
        assertTrue(ollama.embedded.isEmpty());
        assertEquals(List.of("Keys.md skipped 1", "Spec.md skipped 3"), items(r3));
        assertEquals(4, count("SELECT count(*) FROM knowledge_records WHERE allowed_group_ids = '[\"general\",\"contracts\"]'"));

        // Re-read all: every file parsed again, nothing re-embedded (checksums unchanged).
        String r4 = sync.run(entry, null, true);
        assertTrue(ollama.embedded.isEmpty());
        assertEquals(1, ((Number) run(r4).get("reread")).intValue());

        // A chunk embedded by another model (a Gemini outage, or the switch from Ollama) is re-embedded
        // on the next read of its file, with the model it was embedded by stored.
        assertEquals(4, count("SELECT count(*) FROM knowledge_records WHERE embed_model = 'ollama/unused'"));
        db.sql("UPDATE knowledge_records SET embed_model = 'gemini/old' WHERE title = 'Spec > Spec > Auth'").update();
        sync.run(entry, null, true);
        assertEquals(List.of("Spec > Spec > Auth\n\nToken flow."), ollama.embedded);
        assertEquals(4, count("SELECT count(*) FROM knowledge_records WHERE embed_model = 'ollama/unused'"));
        ollama.embedded.clear();

        // A section removed: its chunk is deleted, the rest kept.
        write("Spec.md", "# Spec\n\n## Auth\n\nToken flow.\n\n## Errors\n\nE1001 means both uen and name given.\n", -5);
        sync.run(entry, null, false);
        assertEquals(2, count("SELECT count(*) FROM knowledge_records WHERE parent_id LIKE '%Spec.md'"));
        assertEquals(0, count("SELECT count(*) FROM knowledge_fts WHERE knowledge_fts MATCH 'minute'"));
    }

    @Test
    void modelServerErrorSkipsThatFileAndRetriesNextSync() throws Exception {
        write("Boom.md", "## A\n\nBOOM here.\n", 100);
        write("Good.md", "## B\n\nAll fine.\n", 100);
        String r1 = sync.run(entry, null, false);
        assertEquals("success", run(r1).get("status"));
        assertTrue(String.valueOf(run(r1).get("error_message")).contains("1 skipped after a model server error"));
        assertEquals(List.of("Good.md inserted 1"), items(r1));
        assertTrue(db.sql("SELECT detail FROM ingest_skips WHERE reason = 'model-error'").query(String.class).single().contains("CUDA error"));
        ollama.failOnBoom = false;
        String r2 = sync.run(entry, null, false);
        assertEquals(List.of("Boom.md inserted 1", "Good.md skipped 1"), items(r2));
        assertEquals(0, count("SELECT count(*) FROM ingest_skips WHERE reason = 'model-error'"), "cleared once it went through");
    }

    @Test
    void stopIsCheckedBetweenFiles() throws Exception {
        write("A.md", "## A\n\nFirst file.\n", 100);
        write("B.md", "## B\n\nSecond file.\n", 100);
        String admin = UUID.randomUUID().toString();
        db.sql("INSERT INTO users (id, username, name, password_hash, is_admin) VALUES (?, 'boss', 'Tan Boss', 'x', 1)").param(admin).update();
        ollama.onEmbed = () -> db.sql("UPDATE sync_runs SET stop_requested_by = ?, stop_requested_at = ? WHERE status = 'running'")
                .params(admin, "2026-10-13T09:30:00.000Z").update();
        String r = sync.run(entry, admin, false);
        assertEquals("stopped", run(r).get("status"));
        assertEquals("Stopped by Tan Boss (requested 2026-10-13 09:30)", run(r).get("error_message"));
        assertEquals(List.of("A.md inserted 1"), items(r), "the file in progress finishes; the next one isn't started");
        assertEquals("stopped", db.sql("SELECT last_status FROM sync_sources WHERE id = ?").param(entry).query(String.class).single());
        assertNotNull(db.sql("SELECT last_synced_at FROM sync_sources WHERE id = ?").param(entry).query(String.class).single(),
                "a stopped run counts as a sync, so the scheduler doesn't restart it a minute later");
        assertNotNull(run(r).get("finished_at"));
    }

    @Test
    void failuresAreRecordedAndNothingIsWipedByMistake() throws Exception {
        // Already syncing: refused, no run row.
        db.sql("UPDATE sync_sources SET last_status = 'syncing' WHERE id = ?").param(entry).update();
        assertNull(sync.run(entry, null, false));
        assertEquals(0, count("SELECT count(*) FROM sync_runs"));

        // A restart left it syncing with a run at running: startup marks both failed.
        db.sql("INSERT INTO sync_runs (id, sync_source_id, started_at) VALUES ('r0', ?, '2026-10-13T00:00:00.000Z')").param(entry).update();
        assertEquals(1, sync.resetInterrupted());
        assertEquals(SyncService.INTERRUPTED, run("r0").get("error_message"));
        assertEquals("failed", db.sql("SELECT last_status FROM sync_sources WHERE id = ?").param(entry).query(String.class).single());

        // An emptied folder doesn't wipe the entry: the run fails, the records stay.
        write("Spec.md", "## A\n\nText.\n", 100);
        sync.run(entry, null, false);
        Files.delete(folder.resolve("Spec.md"));
        String r = sync.run(entry, null, false);
        assertEquals("failed", run(r).get("status"));
        assertTrue(String.valueOf(run(r).get("error_message")).contains("listed nothing"));
        assertEquals(1, count("SELECT count(*) FROM knowledge_records"));

        // A missing folder fails the run with the reason, never left at running.
        db.sql("UPDATE sync_sources SET config = ? WHERE id = ?").params(json.writeValueAsString(Map.of("folder_path", tmp.resolve("nope").toString())), entry).update();
        String r2 = sync.run(entry, null, false);
        assertEquals("failed", run(r2).get("status"));
        assertTrue(String.valueOf(run(r2).get("error_message")).contains("folder_path not found"));
        assertEquals(0, count("SELECT count(*) FROM sync_runs WHERE status = 'running'"));

        // A deleted entry is never synced.
        db.sql("UPDATE sync_sources SET deleted_at = '2026-10-13T00:00:00.000Z', last_status = 'failed' WHERE id = ?").param(entry).update();
        assertNull(sync.run(entry, null, false));
    }
}
