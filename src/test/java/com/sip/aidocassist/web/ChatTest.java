package com.sip.aidocassist.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.client.ResourceAccessException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 6 end to end: the chat pages over Phase 4/5 with a scripted model. New thread, follow-up
 * with rewrite, citation chips and the evidence panel, abstention, private threads, rename, delete,
 * evidence re-checked against current access, Ollama down, and the answer text escaped.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatTest {

    static final Path tmp;
    static {
        try { tmp = Files.createTempDirectory("aidocassist-chat"); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("gemini.api-keys", () -> ""); // never the real outside services from .env
        r.add("deepseek.api-key", () -> "");
        r.add("db.path", () -> tmp.resolve("chat.db").toString());
        r.add("upload.dir", () -> tmp.resolve("uploads").toString());
        r.add("admin.username", () -> "");
        r.add("admin.email", () -> "");
        r.add("admin.password", () -> "");
        r.add("sync.scheduler", () -> "false");
        r.add("chat.examples", () -> "What does error code E1001 mean? | How do I fix it?");
    }

    /** Embeddings: one fixed unit vector. Chat: scripted replies; can act as an unreachable server. */
    static class ScriptedModel extends OllamaClient {
        final Deque<String> replies = new ArrayDeque<>();
        volatile boolean down;
        ScriptedModel() { super("http://unused", "unused"); }
        @Override public float[][] embed(List<String> texts) {
            if (down) throw new ResourceAccessException("connection refused");
            float[][] out = new float[texts.size()][DIM];
            for (float[] v : out) v[0] = 1f;
            return out;
        }
        @Override public JsonNode chat(String system, String user, Map<String, Object> schema) {
            try { return new ObjectMapper().readTree(replies.removeFirst()); } catch (Exception e) { throw new IllegalStateException(e); }
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean @Primary ScriptedModel scriptedModel() { return new ScriptedModel(); }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired BCryptPasswordEncoder encoder;
    @Autowired ScriptedModel model;

    static final String ANSWER = """
            {"answerable": true, "answer": "E1001 means both uen and name were provided <script>alert(1)</script> [E1].",
             "citations": [{"label": "E1", "quote": "E1001 | Both uen and name provided — specify only one at a time."}]}""";

    MockHttpSession login(String username, String... groups) throws Exception {
        db.sql("INSERT OR IGNORE INTO groups (id, name) VALUES ('g1', 'general'), ('g2', 'finance')").update();
        if (db.sql("SELECT count(*) FROM users WHERE username = ?").param(username).query(Integer.class).single() == 0) {
            String id = UUID.randomUUID().toString();
            db.sql("INSERT INTO users (id, username, name, password_hash) VALUES (?, ?, ?, ?)")
                    .params(id, username, "User " + username, encoder.encode("pw-123456789")).update();
            for (String g : groups) db.sql("INSERT INTO user_groups SELECT ?, id FROM groups WHERE name = ?").params(id, g).update();
        }
        return (MockHttpSession) mvc.perform(post("/login").with(csrf()).param("username", username).param("password", "pw-123456789"))
                .andReturn().getRequest().getSession();
    }

    void docs() {
        if (db.sql("SELECT count(*) FROM knowledge_records").query(Integer.class).single() > 0) return;
        db.sql("INSERT INTO sync_sources (id, source, name, config, default_group_ids) VALUES ('acra', 'filesystem', 'Specs', '{}', '[\"general\"]')").update();
        String[][] rows = {
                {"r1", "C:/specs/ACRA_NGM_API_Specification_v1.pdf#28", "ACRA_NGM_API_Specification_v1 > 4. Permissible Disclosure API > 4.6 Error Responses > 400 — Bad Request",
                        "| Error Code | Description | Resolution |\n| --- | --- | --- |\n| E1001 | Both uen and name provided — specify only one at a time. | Remove either uen or name from the request. |"},
                {"r2", "C:/specs/ACRA_NGM_API_Specification_v1.pdf#29", "ACRA_NGM_API_Specification_v1 > 4. Permissible Disclosure API > 4.6 Error Responses > Other HTTP Status Codes",
                        "| Status | Meaning |\n| --- | --- |\n| 401 | The token is missing or expired. |"}};
        for (String[] r : rows)
            db.sql("""
                    INSERT INTO knowledge_records (id, source, source_id, parent_id, source_url, source_type, title, content, raw_content, updated_at,
                        allowed_group_ids, sync_source_id, content_checksum, embedding)
                    VALUES (?, 'filesystem', ?, 'C:/specs/ACRA_NGM_API_Specification_v1.pdf', 'file:///C:/specs/ACRA.pdf', 'doc-section', ?, ?, ?,
                        '2026-10-13T00:00:00.000Z', '["general"]', 'acra', 'x', ?)""")
                    .params(r[0], r[1], r[2], r[3], r[3], OllamaClient.toBytes(new float[]{1f})).update();
    }

    String askNew(MockHttpSession s, String question) throws Exception {
        String location = mvc.perform(post("/ask").session(s).with(csrf()).param("question", question))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertTrue(location.matches("/t/[0-9a-f-]{36}#latest"), location);
        return location.substring(3, 39);
    }

    @Test
    void askFollowUpEvidenceAndThreadManagement() throws Exception {
        docs();
        var mei = login("mei", "general");
        mvc.perform(get("/").session(mei)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Start a new thread")))
                .andExpect(content().string(containsString(">General<"))) // scope chip
                .andExpect(content().string(containsString("What does error code E1001 mean?"))) // chat.examples
                .andExpect(content().string(containsString("No threads yet.")));

        // A first question starts a thread titled with it.
        model.replies.add(ANSWER);
        String tid = askNew(mei, "What does error code E1001 mean?");
        assertEquals("What does error code E1001 mean?", db.sql("SELECT title FROM threads WHERE id = ?").param(tid).query(String.class).single());
        String page = mvc.perform(get("/t/" + tid).session(mei)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("class=\"q-text\">What does error code E1001 mean?</div>"));
        assertTrue(page.contains("&lt;script&gt;alert(1)&lt;/script&gt;") && !page.contains("<script>alert(1)"), "the model's text is escaped");
        assertTrue(page.contains("class=\"cite\" href=\"#ev-") && page.contains("aria-label=\"Evidence E1\">E1</a>"), "[E1] became a chip");
        assertTrue(page.contains("400 — Bad Request</span>"), "chip title from knowledge_records");
        assertTrue(page.contains("“E1001 | Both uen and name provided — specify only one at a time.”"), "the quote in the evidence panel");
        assertTrue(page.contains("C:/specs") || page.contains("Specification_v1"), "a filesystem file isn't a link; its title/path is shown");
        assertFalse(page.contains("Open in Filesystem"), "file:// isn't linkable from a web page");
        assertTrue(page.contains("+1 more found, not used in this answer"), "the 401 row is suggested");
        assertTrue(page.contains("id=\"latest\""));

        // A follow-up: rewritten from the thread before retrieval; the rewrite is stored and shown.
        model.replies.add("{\"question\": \"How do I fix error code E1001?\"}");
        model.replies.add("""
                {"answerable": true, "answer": "Remove either uen or name from the request [E1].",
                 "citations": [{"label": "E1", "quote": "E1001 | Both uen and name provided — specify only one at a time. | Remove either uen or name from the request."}]}""");
        mvc.perform(post("/ask").session(mei).with(csrf()).param("question", "How do I fix it?").param("thread_id", tid))
                .andExpect(redirectedUrl("/t/" + tid + "#latest"));
        assertEquals("How do I fix error code E1001?",
                db.sql("SELECT reformed_query FROM messages WHERE thread_id = ? AND role = 'user' AND content = 'How do I fix it?'").param(tid).query(String.class).single());
        mvc.perform(get("/t/" + tid).session(mei)).andExpect(content().string(containsString("How do I fix error code E1001?")))
                .andExpect(content().string(containsString("(your follow-up, rewritten using the thread)")))
                .andExpect(content().string(containsString("2 turns")));

        // An unanswerable question: an honest abstention, rendered inline, with suggestions.
        model.replies.add("{\"answerable\": false, \"answer\": \"No.\", \"citations\": []}");
        String t2 = askNew(mei, "How many days of annual leave do staff get?");
        mvc.perform(get("/t/" + t2).session(mei)).andExpect(content().string(containsString("class=\"a abstain\"")))
                .andExpect(content().string(containsString("I couldn&#39;t find enough evidence")))
                .andExpect(content().string(containsString("Ask another question")));

        // Private: another user can't open, rename, delete or post into this thread.
        var other = login("other", "general");
        mvc.perform(get("/t/" + tid).session(other)).andExpect(redirectedUrl("/"));
        mvc.perform(post("/t/" + tid + "/rename").session(other).with(csrf()).param("title", "mine now")).andExpect(redirectedUrl("/"));
        mvc.perform(post("/t/" + tid + "/delete").session(other).with(csrf()).param("current", tid)).andExpect(redirectedUrl("/"));
        mvc.perform(post("/ask").session(other).with(csrf()).param("question", "x").param("thread_id", tid)).andExpect(redirectedUrl("/"));
        mvc.perform(get("/").session(other)).andExpect(content().string(not(containsString("What does error code E1001 mean?</span>"))));
        assertEquals("What does error code E1001 mean?", db.sql("SELECT title FROM threads WHERE id = ?").param(tid).query(String.class).single());

        // Rename (owner).
        mvc.perform(post("/t/" + tid + "/rename").session(mei).with(csrf()).param("title", "  E1001   questions ")).andExpect(redirectedUrl("/t/" + tid));
        assertEquals("E1001 questions", db.sql("SELECT title FROM threads WHERE id = ?").param(tid).query(String.class).single());

        // Evidence is re-checked against CURRENT access: the entry disabled -> "no longer available".
        db.sql("UPDATE sync_sources SET enabled = 0 WHERE id = 'acra'").update();
        mvc.perform(get("/t/" + tid).session(mei)).andExpect(content().string(containsString("This evidence is no longer available to you")))
                .andExpect(content().string(not(containsString("+1 more found"))));
        db.sql("UPDATE sync_sources SET enabled = 1 WHERE id = 'acra'").update();

        // Delete (owner): thread and messages gone; deleting the open thread lands on the new-thread page.
        mvc.perform(post("/t/" + t2 + "/delete").session(mei).with(csrf()).param("current", tid)).andExpect(redirectedUrl("/t/" + tid));
        mvc.perform(post("/t/" + tid + "/delete").session(mei).with(csrf()).param("current", tid)).andExpect(redirectedUrl("/"));
        assertEquals(0, db.sql("SELECT count(*) FROM threads WHERE user_id = (SELECT id FROM users WHERE username = 'mei')").query(Integer.class).single());
        assertEquals(0, db.sql("SELECT count(*) FROM messages WHERE thread_id IN (?, ?)").params(tid, t2).query(Integer.class).single());
        mvc.perform(post("/t/x/delete").session(mei).with(csrf()).param("current", "//evil.test")).andExpect(redirectedUrl("/"));
    }

    @Test
    void ollamaDownKeepsTheQuestionAndSavesNothing() throws Exception {
        docs();
        var s = login("down", "general");
        model.down = true;
        try {
            mvc.perform(post("/ask").session(s).with(csrf()).param("question", "What does error code E1001 mean?"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().string(containsString("The model server can&#39;t be reached")))
                    .andExpect(content().string(containsString(">What does error code E1001 mean?</textarea>")));
        } finally {
            model.down = false;
        }
        assertEquals(0, db.sql("SELECT count(*) FROM threads WHERE user_id = (SELECT id FROM users WHERE username = 'down')").query(Integer.class).single());
        // No groups: nothing searched, an honest abstention.
        var nobody = login("nogroups");
        String tid = askNew(nobody, "What does error code E1001 mean?");
        mvc.perform(get("/t/" + tid).session(nobody)).andExpect(content().string(containsString("No groups")))
                .andExpect(content().string(containsString("I couldn&#39;t find enough evidence")));
    }
}
