package com.sip.aidocassist;

import com.sip.aidocassist.db.Migrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The schema applies (twice, idempotently), the connection pragmas hold, and FTS follows knowledge_records. */
class SchemaTest {

    @Test
    void schemaPragmasAndFts(@TempDir Path tmp) throws Exception {
        try (var ds = (com.zaxxer.hikari.HikariDataSource) new AiDocAssistApplication().dataSource(tmp.resolve("sub/test.db").toString())) {
            check(ds);
        }
    }

    private static void check(javax.sql.DataSource ds) throws Exception {
        Migrator.migrate(ds);
        Migrator.migrate(ds); // second start applies nothing
        var db = JdbcClient.create(ds);

        assertEquals("wal", db.sql("PRAGMA journal_mode").query(String.class).single());
        assertEquals(1, db.sql("PRAGMA foreign_keys").query(Integer.class).single());
        assertEquals(5000, db.sql("PRAGMA busy_timeout").query(Integer.class).single());
        assertEquals(List.of("001_schema.sql", "002_username.sql", "003_embed_model.sql"), db.sql("SELECT name FROM schema_migrations").query(String.class).list());

        // Foreign keys are enforced: a membership for a user that doesn't exist is rejected.
        assertThrows(Exception.class, () -> db.sql("INSERT INTO user_groups VALUES ('nobody', 'nogroup')").update());

        db.sql("INSERT INTO knowledge_records (id, source, source_id, parent_id, source_type, title, content, updated_at, content_checksum) "
                + "VALUES ('r1', 'filesystem', 'a.docx#0', 'a.docx', 'doc-section', 'Settlement cutoff', 'Payments are batched nightly', '2026-10-13T00:00:00Z', 'x')").update();
        assertEquals(List.of("r1"), match(db, "batching")); // porter stemming: batching -> batch
        assertEquals(List.of("r1"), match(db, "title:settlement"));

        db.sql("UPDATE knowledge_records SET content = 'Refunds go out weekly' WHERE id = 'r1'").update();
        assertEquals(List.of(), match(db, "batched"));
        assertEquals(List.of("r1"), match(db, "refund"));

        db.sql("UPDATE knowledge_records SET embedding = x'00' WHERE id = 'r1'").update(); // not a text change
        assertEquals(List.of("r1"), match(db, "refund"));

        db.sql("DELETE FROM knowledge_records WHERE id = 'r1'").update();
        assertEquals(List.of(), match(db, "refund"));
        // Throws if the index disagrees with knowledge_records (rank=1 also checks against the content table).
        db.sql("INSERT INTO knowledge_fts(knowledge_fts, rank) VALUES ('integrity-check', 1)").update();
    }

    private static List<String> match(JdbcClient db, String q) {
        return db.sql("SELECT k.id FROM knowledge_fts f JOIN knowledge_records k ON k.rowid = f.rowid WHERE knowledge_fts MATCH ?")
                .param(q).query(String.class).list();
    }
}
