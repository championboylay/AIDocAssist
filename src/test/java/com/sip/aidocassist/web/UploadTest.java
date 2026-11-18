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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 3 end to end (CLAUDE.md "Upload"): the Add upload dialog (one entry per file, tags, refusals,
 * same name = new version keeping its tags), a row's Sync = a new version under another name,
 * downloads with the group check, and Delete removing everything. Ollama is a stand-in.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UploadTest {

    static final Path tmp;
    static {
        try { tmp = Files.createTempDirectory("aidocassist-upload"); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("gemini.api-keys", () -> ""); // never the real outside services from .env
        r.add("deepseek.api-key", () -> "");
        r.add("db.path", () -> tmp.resolve("upload.db").toString());
        r.add("upload.dir", () -> tmp.resolve("uploads").toString());
        r.add("admin.username", () -> "");
        r.add("admin.email", () -> "");
        r.add("admin.password", () -> "");
        r.add("sync.scheduler", () -> "false");
    }

    static class FakeOllama extends OllamaClient {
        FakeOllama() { super("http://unused", "unused"); }
        @Override public float[][] embed(List<String> texts) {
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
    @Autowired SyncQueue queue;

    MockHttpSession login(String username, boolean admin, String... groups) throws Exception {
        db.sql("INSERT OR IGNORE INTO groups (id, name) VALUES ('g1', 'general'), ('g2', 'finance')").update();
        if (db.sql("SELECT count(*) FROM users WHERE username = ?").param(username).query(Integer.class).single() == 0) {
            String id = UUID.randomUUID().toString();
            db.sql("INSERT INTO users (id, username, name, password_hash, is_admin) VALUES (?, ?, ?, ?, ?)")
                    .params(id, username, username, encoder.encode("pw-123456789"), admin).update();
            for (String g : groups) db.sql("INSERT INTO user_groups SELECT ?, id FROM groups WHERE name = ?").params(id, g).update();
        }
        return (MockHttpSession) mvc.perform(post("/login").with(csrf()).param("username", username).param("password", "pw-123456789"))
                .andReturn().getRequest().getSession();
    }

    static MockMultipartFile file(String name, String text) {
        return new MockMultipartFile("files", name, "application/octet-stream", text.getBytes(StandardCharsets.UTF_8));
    }

    void awaitIdle() throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (db.sql("SELECT count(*) FROM sync_runs WHERE status = 'running'").query(Integer.class).single() > 0
                || db.sql("SELECT id FROM sync_sources").query(String.class).list().stream().anyMatch(queue::isPending)) {
            if (System.currentTimeMillis() > deadline) fail("sync did not finish");
            Thread.sleep(20);
        }
    }

    String entry(String name) {
        return db.sql("SELECT id FROM sync_sources WHERE source = 'upload' AND name = ?").param(name).query(String.class).single();
    }

    @Test
    void uploadVersionsDownloadAndDelete() throws Exception {
        var admin = login("admin", true);

        // Nothing chosen / no tag: refused with the reason.
        mvc.perform(multipart("/admin/sync/upload").file(file("A.md", "x")).session(admin).with(csrf()))
                .andExpect(header().string("Location", containsString("pick+at+least+one+tag")));

        // Add upload: two files become two entries, tagged finance, stored and synced. A wrong type is refused before
        // any entry exists; a path in the name is stripped; an oversized file is refused and leaves no entry.
        byte[] big = new byte[(int) (20L * 1024 * 1024) + 1];
        mvc.perform(multipart("/admin/sync/upload")
                        .file(file("Payments Runbook.md", "## Cut-off\n\nPayments are batched at 18:00.\n"))
                        .file(file("..\\..\\notes.txt", "Escalate to the duty manager."))
                        .file(file("tool.exe", "MZ"))
                        .file(new MockMultipartFile("files", "huge.pdf", "application/pdf", big))
                        .param("tags", "finance").session(admin).with(csrf()))
                .andExpect(header().string("Location", containsString("Uploaded+2")))
                .andExpect(header().string("Location", containsString("tool.exe+%28type+not+supported")))
                .andExpect(header().string("Location", containsString("huge.pdf+%28over+20+MB%29")));
        awaitIdle();
        assertEquals(List.of("Payments Runbook.md", "notes.txt"),
                db.sql("SELECT name FROM sync_sources WHERE source = 'upload' ORDER BY name").query(String.class).list());
        String runbook = entry("Payments Runbook.md");
        assertTrue(Files.isRegularFile(tmp.resolve("uploads").resolve(runbook).resolve("Payments Runbook.md")));
        assertEquals(List.of(runbook + "/Payments Runbook.md#0"),
                db.sql("SELECT source_id FROM knowledge_records WHERE sync_source_id = ?").param(runbook).query(String.class).list());
        assertEquals("[\"finance\"]", db.sql("SELECT DISTINCT allowed_group_ids FROM knowledge_records WHERE source = 'upload'").query(String.class).single());
        assertEquals("/files/" + runbook + "/Payments Runbook.md",
                db.sql("SELECT source_url FROM knowledge_records WHERE sync_source_id = ?").param(runbook).query(String.class).single());
        mvc.perform(get("/admin/sync").session(admin)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Payments Runbook.md"))).andExpect(content().string(containsString("Download")))
                .andExpect(content().string(containsString("Add upload")));

        // Same name, other capitals: a new version of that entry; it keeps ITS tags and says the dialog's don't apply.
        mvc.perform(multipart("/admin/sync/upload").file(file("payments runbook.MD", "## Cut-off\n\nPayments are batched at 17:30.\n"))
                        .param("tags", "general").session(admin).with(csrf()))
                .andExpect(header().string("Location", containsString("New+version+of+1")))
                .andExpect(header().string("Location", containsString("kept+its+tags%3A+finance")));
        awaitIdle();
        assertEquals(1, db.sql("SELECT count(*) FROM sync_sources WHERE source = 'upload' AND lower(name) = 'payments runbook.md'").query(Integer.class).single());
        assertTrue(db.sql("SELECT content FROM knowledge_records WHERE sync_source_id = ?").param(runbook).query(String.class).single().contains("17:30"));
        assertEquals("[\"finance\"]", db.sql("SELECT allowed_group_ids FROM knowledge_records WHERE sync_source_id = ?").param(runbook).query(String.class).single());

        // A row's Sync = a new version, here under another file name: the entry keeps its name, the old file and its records go.
        mvc.perform(get("/admin/sync/" + runbook + "/run").session(admin)).andExpect(content().string(containsString("Drag the new version here")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Re-read all"))));
        mvc.perform(multipart("/admin/sync/" + runbook + "/run").file(file("Runbook v2.md", "## Cut-off\n\nNow 17:00.\n")).session(admin).with(csrf()))
                .andExpect(header().string("Location", containsString("New+version+of+Payments+Runbook.md+stored+%28Runbook+v2.md%29")));
        awaitIdle();
        assertEquals(List.of("Runbook v2.md"), Files.list(tmp.resolve("uploads").resolve(runbook)).map(p -> p.getFileName().toString()).toList());
        assertEquals(List.of(runbook + "/Runbook v2.md#0"),
                db.sql("SELECT source_id FROM knowledge_records WHERE sync_source_id = ?").param(runbook).query(String.class).list());
        mvc.perform(multipart("/admin/sync/" + runbook + "/run").session(admin).with(csrf()))
                .andExpect(header().string("Location", containsString("choose+the+new+version")));

        // Downloads: admin yes; a finance user yes; a general-only user, a missing file or a path trick: the same 404.
        mvc.perform(get("/files/" + runbook + "/Runbook v2.md").session(admin)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().string(containsString("Now 17:00.")));
        mvc.perform(get("/files/" + runbook + "/Runbook v2.md").session(login("fin", false, "finance"))).andExpect(status().isOk());
        var general = login("gen", false, "general");
        mvc.perform(get("/files/" + runbook + "/Runbook v2.md").session(general)).andExpect(status().isNotFound());
        mvc.perform(get("/files/" + runbook + "/nope.md").session(admin)).andExpect(status().isNotFound());
        // An encoded "../" never reaches the controller: Spring Security's firewall rejects it first (400).
        mvc.perform(get("/files/" + runbook + "/..%2F..%2Fupload.db").session(admin)).andExpect(status().isBadRequest());
        Files.writeString(tmp.resolve("uploads").resolve(runbook).resolve(".upload-inprogress"), "partial");
        mvc.perform(get("/files/" + runbook + "/.upload-inprogress").session(admin)).andExpect(status().isNotFound());
        Files.delete(tmp.resolve("uploads").resolve(runbook).resolve(".upload-inprogress"));
        db.sql("UPDATE sync_sources SET enabled = 0 WHERE id = ?").param(runbook).update();
        mvc.perform(get("/files/" + runbook + "/Runbook v2.md").session(login("fin", false))).andExpect(status().isNotFound());
        db.sql("UPDATE sync_sources SET enabled = 1 WHERE id = ?").param(runbook).update();
        mvc.perform(get("/admin/sync").session(general)).andExpect(status().isForbidden());
        mvc.perform(multipart("/admin/sync/upload").file(file("x.md", "x")).param("tags", "general").session(general).with(csrf()))
                .andExpect(status().isForbidden());

        // Edit: only tags and Enabled change for an upload; a tag change applies at once.
        mvc.perform(post("/admin/sync/" + runbook).session(admin).with(csrf()).param("name", "renamed").param("tags", "general").param("enabled", "on"))
                .andExpect(header().string("Location", containsString("Tags+applied")));
        assertEquals("Payments Runbook.md", db.sql("SELECT name FROM sync_sources WHERE id = ?").param(runbook).query(String.class).single());
        assertEquals("[\"general\"]", db.sql("SELECT allowed_group_ids FROM knowledge_records WHERE sync_source_id = ?").param(runbook).query(String.class).single());

        // Delete: the file, its records, its history and the entry are gone.
        mvc.perform(post("/admin/sync/" + runbook + "/delete").session(admin).with(csrf()))
                .andExpect(header().string("Location", containsString("its+sync+history+are+gone")));
        assertFalse(Files.exists(tmp.resolve("uploads").resolve(runbook)));
        for (String sql : List.of("SELECT count(*) FROM sync_sources WHERE id = ?", "SELECT count(*) FROM knowledge_records WHERE sync_source_id = ?",
                "SELECT count(*) FROM sync_runs WHERE sync_source_id = ?", "SELECT count(*) FROM ingest_skips WHERE sync_source_id = ?"))
            assertEquals(0, db.sql(sql).param(runbook).query(Integer.class).single(), sql);
        assertEquals(0, db.sql("SELECT count(*) FROM sync_run_items i LEFT JOIN sync_runs r ON r.id = i.sync_run_id WHERE r.id IS NULL").query(Integer.class).single());
        mvc.perform(get("/files/" + runbook + "/Runbook v2.md").session(admin)).andExpect(status().isNotFound());
    }
}
