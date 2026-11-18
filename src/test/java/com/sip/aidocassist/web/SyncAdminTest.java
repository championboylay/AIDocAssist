package com.sip.aidocassist.web;

import com.sip.aidocassist.ingestion.SyncQueue;
import com.sip.aidocassist.ollama.OllamaClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 2c end to end: the Sync admin tab on a throwaway database and a real folder, with Ollama
 * replaced by a stand-in. Add/validate entries, Synchronize now through the queue, history, run
 * detail, tag change applied at once, Stop, Delete, and the queue refusing a second submit.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SyncAdminTest {

    // Not @TempDir: the cached Spring context keeps the database open after the tests (Windows can't delete it).
    static final Path tmp;
    static {
        try { tmp = Files.createTempDirectory("aidocassist-sync"); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("gemini.api-keys", () -> ""); // never the real outside services from .env
        r.add("deepseek.api-key", () -> "");
        r.add("db.path", () -> tmp.resolve("sync-admin.db").toString());
        r.add("admin.username", () -> "");
        r.add("admin.email", () -> "");
        r.add("admin.password", () -> "");
        r.add("sync.scheduler", () -> "false"); // the real ticker would start syncs inside a cached test context
    }

    /** Unit vectors; can block until released, to hold a sync "running". */
    static class FakeOllama extends OllamaClient {
        volatile CountDownLatch gate = new CountDownLatch(0);
        FakeOllama() { super("http://unused", "unused"); }
        @Override
        public float[][] embed(List<String> texts) {
            try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            float[][] out = new float[texts.size()][DIM];
            for (float[] v : out) v[0] = 1f;
            return out;
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean @Primary FakeOllama fakeOllama() { return new FakeOllama(); }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired BCryptPasswordEncoder encoder;
    @Autowired FakeOllama ollama;
    @Autowired SyncQueue queue;

    MockHttpSession login(String username, boolean admin) throws Exception {
        if (db.sql("SELECT count(*) FROM users WHERE username = ?").param(username).query(Integer.class).single() == 0) {
            db.sql("INSERT INTO users (id, username, name, password_hash, is_admin) VALUES (?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), username, "Admin " + username.charAt(0), encoder.encode("pw-123456789"), admin).update();
            db.sql("INSERT OR IGNORE INTO groups (id, name) VALUES ('g1', 'general'), ('g2', 'finance')").update();
        }
        return (MockHttpSession) mvc.perform(post("/login").with(csrf()).param("username", username).param("password", "pw-123456789"))
                .andReturn().getRequest().getSession();
    }

    /** Condition-based wait for the background sync: until no run of the entry is running and it isn't queued. */
    void awaitIdle(String entry) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (queue.isPending(entry) || db.sql("SELECT count(*) FROM sync_runs WHERE sync_source_id = ? AND status = 'running'")
                .param(entry).query(Integer.class).single() > 0) {
            if (System.currentTimeMillis() > deadline) fail("sync did not finish");
            Thread.sleep(20);
        }
    }

    String entryId(String name) {
        return db.sql("SELECT id FROM sync_sources WHERE name = ?").param(name).query(String.class).single();
    }

    @Test
    void addSyncHistoryRetagDelete() throws Exception {
        var s = login("admin", true);
        Path docs = Files.createDirectories(tmp.resolve("docs"));
        Files.writeString(docs.resolve("Runbook.md"), "## Restart\n\nStop then start.\n\n## Verify\n\nCheck health.\n");
        Files.writeString(docs.resolve("draft.tmp"), "x");

        // Validation: no tags, a folder that doesn't exist.
        mvc.perform(post("/admin/sync/new").session(s).with(csrf()).param("name", "Docs").param("folder_path", tmp.resolve("nope").toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Pick at least one tag")))
                .andExpect(content().string(containsString("doesn&#39;t exist")));

        mvc.perform(post("/admin/sync/new").session(s).with(csrf()).param("name", "Docs").param("folder_path", docs.toString())
                        .param("tags", "general").param("enabled", "on"))
                .andExpect(redirectedUrl("/admin/sync?src=filesystem&msg=Added+Docs.+Run+Sync+to+pull+its+documents."));
        String id = entryId("Docs");

        // Overlap: a subfolder of an existing entry is refused.
        Files.createDirectories(docs.resolve("sub"));
        mvc.perform(post("/admin/sync/new").session(s).with(csrf()).param("name", "Sub").param("folder_path", docs.resolve("sub").toString())
                        .param("tags", "general"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("overlaps the entry &quot;Docs&quot;")));

        mvc.perform(get("/admin/sync?src=filesystem").session(s)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Not synced yet"))).andExpect(content().string(containsString(docs.toString())))
                .andExpect(content().string(containsString("Every 24 h · next: soon")));
        assertEquals(1440, db.sql("SELECT sync_interval_minutes FROM sync_sources WHERE id = ?").param(id).query(Integer.class).single(),
                "a new filesystem entry syncs every 24 h unless the admin picks otherwise");
        mvc.perform(get("/admin/sync/new").session(s)).andExpect(content().string(containsString("<option value=\"1440\" selected=\"selected\">Every 24 h</option>")));

        // Synchronize now -> the queue -> history.
        mvc.perform(post("/admin/sync/" + id + "/run").session(s).with(csrf()))
                .andExpect(redirectedUrl("/admin/sync?src=filesystem&msg=Sync+of+Docs+started."));
        awaitIdle(id);
        mvc.perform(get("/admin/sync?src=filesystem").session(s)).andExpect(content().string(containsString("Synced")))
                .andExpect(content().string(containsString("1 file"))).andExpect(content().string(containsString("1 skipped")));
        String runId = db.sql("SELECT id FROM sync_runs WHERE sync_source_id = ?").param(id).query(String.class).single();
        assertEquals("admin", db.sql("SELECT u.username FROM sync_runs r JOIN users u ON u.id = r.triggered_by WHERE r.id = ?")
                .param(runId).query(String.class).single(), "the admin is recorded as the trigger");
        mvc.perform(get("/admin/sync/" + id + "/history").session(s)).andExpect(status().isOk())
                .andExpect(content().string(containsString("success"))).andExpect(content().string(containsString("Admin a")));
        mvc.perform(get("/admin/sync/" + id + "/runs/" + runId).session(s)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Runbook.md"))).andExpect(content().string(containsString("inserted (1)")));
        mvc.perform(get("/admin/sync/" + id + "/run").session(s)).andExpect(status().isOk()).andExpect(content().string(containsString("Start sync")));

        // A tag change applies to existing records at once, not at the next sync.
        mvc.perform(post("/admin/sync/" + id).session(s).with(csrf()).param("name", "Docs").param("folder_path", docs.toString())
                        .param("tags", "finance").param("enabled", "on"))
                .andExpect(redirectedUrl("/admin/sync?src=filesystem&msg=Saved+Docs.+Tags+applied+to+its+2+existing+section%28s%29."));
        assertEquals(2, db.sql("SELECT count(*) FROM knowledge_records WHERE allowed_group_ids = '[\"finance\"]'").query(Integer.class).single());

        // Schedule: Off stores NULL (manual only); a value not on the list is refused.
        mvc.perform(post("/admin/sync/" + id).session(s).with(csrf()).param("name", "Docs").param("folder_path", docs.toString())
                .param("tags", "finance").param("enabled", "on").param("schedule", "off"));
        assertNull(db.sql("SELECT sync_interval_minutes FROM sync_sources WHERE id = ?").param(id).query(Integer.class).single());
        mvc.perform(get("/admin/sync?src=filesystem").session(s)).andExpect(content().string(containsString("Manual only")));
        mvc.perform(post("/admin/sync/" + id).session(s).with(csrf()).param("name", "Docs").param("folder_path", docs.toString())
                        .param("tags", "finance").param("schedule", "7"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("Pick a schedule from the list.")));
        mvc.perform(post("/admin/sync/" + id).session(s).with(csrf()).param("name", "Docs").param("folder_path", docs.toString())
                .param("tags", "finance").param("enabled", "on").param("schedule", "360"));
        assertEquals(360, db.sql("SELECT sync_interval_minutes FROM sync_sources WHERE id = ?").param(id).query(Integer.class).single());

        // Stop asks the running run; with nothing running it says so.
        mvc.perform(post("/admin/sync/" + id + "/stop").session(s).with(csrf()))
                .andExpect(header().string("Location", containsString("Nothing+to+stop")));
        db.sql("INSERT INTO sync_runs (id, sync_source_id, started_at) VALUES ('live', ?, '2026-10-13T00:00:00.000Z')").param(id).update();
        mvc.perform(post("/admin/sync/" + id + "/stop").session(s).with(csrf()).param("back", "history"))
                .andExpect(header().string("Location", containsString("/admin/sync/" + id + "/history?msg=Stopping")));
        assertNotNull(db.sql("SELECT stop_requested_by FROM sync_runs WHERE id = 'live'").query(String.class).single());
        db.sql("UPDATE sync_runs SET status = 'stopped' WHERE id = 'live'").update();

        // Edit and delete are refused while syncing.
        db.sql("UPDATE sync_sources SET last_status = 'syncing' WHERE id = ?").param(id).update();
        mvc.perform(post("/admin/sync/" + id).session(s).with(csrf()).param("name", "Docs").param("folder_path", docs.toString()).param("tags", "general"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("queued or running")));
        mvc.perform(post("/admin/sync/" + id + "/delete").session(s).with(csrf())).andExpect(header().string("Location", containsString("Not+deleted")));
        db.sql("UPDATE sync_sources SET last_status = 'ok' WHERE id = ?").param(id).update();

        // Delete: records and skips gone, entry listed under Deleted entries, history still viewable.
        mvc.perform(post("/admin/sync/" + id + "/delete").session(s).with(csrf())).andExpect(header().string("Location", containsString("Deleted+Docs")));
        assertEquals(0, db.sql("SELECT count(*) FROM knowledge_records").query(Integer.class).single());
        assertEquals(0, db.sql("SELECT count(*) FROM ingest_skips").query(Integer.class).single());
        mvc.perform(get("/admin/sync?src=filesystem").session(s)).andExpect(content().string(containsString("Deleted entries (1)")))
                .andExpect(content().string(not(containsString("Not synced yet"))));
        mvc.perform(get("/admin/sync/" + id + "/history").session(s)).andExpect(status().isOk())
                .andExpect(content().string(containsString("only this history is kept")));
        mvc.perform(post("/admin/sync/" + id + "/run").session(s).with(csrf())).andExpect(redirectedUrl("/admin/sync"));
    }

    @Test
    void queueRunsOneAtATimeAndNeverTwiceForOneEntry() throws Exception {
        var s = login("queue", true);
        Path a = Files.createDirectories(tmp.resolve("qa"));
        Files.writeString(a.resolve("A.md"), "## A\n\nText.\n");
        mvc.perform(post("/admin/sync/new").session(s).with(csrf()).param("name", "QA").param("folder_path", a.toString()).param("tags", "general").param("enabled", "on"));
        String id = entryId("QA");

        ollama.gate = new CountDownLatch(1); // hold the sync inside the embedding call
        mvc.perform(post("/admin/sync/" + id + "/run").session(s).with(csrf())).andExpect(redirectedUrl("/admin/sync?src=filesystem&msg=Sync+of+QA+started."));
        assertFalse(queue.submit(id, null, false), "already queued or running: not queued again");
        mvc.perform(post("/admin/sync/" + id + "/run").session(s).with(csrf())).andExpect(header().string("Location", containsString("Not+started")));
        mvc.perform(get("/admin/sync?src=filesystem").session(s)).andExpect(content().string(containsString("refreshes every 5 seconds")));
        ollama.gate.countDown();
        awaitIdle(id);
        assertEquals(1, db.sql("SELECT count(*) FROM sync_runs WHERE sync_source_id = ?").param(id).query(Integer.class).single());

        // A non-admin can't reach any of it.
        var user = login("user", false);
        mvc.perform(get("/admin/sync?src=filesystem").session(user)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/sync/" + id + "/run").session(user).with(csrf())).andExpect(status().isForbidden());
    }
}
