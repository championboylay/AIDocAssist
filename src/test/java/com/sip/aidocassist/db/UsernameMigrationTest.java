package com.sip.aidocassist.db;

import com.sip.aidocassist.AiDocAssistApplicationAccess;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 002_username.sql on a database built with 001 only and holding data, as the owner's real
 * database does when it next starts: usernames derived from emails, nothing lost, every reference
 * to a user (groups, threads, sync history) still pointing at the right row.
 */
class UsernameMigrationTest {

    @TempDir Path tmp;

    @Test
    void upgradesExistingUsersAndKeepsEveryReference() throws Exception {
        try (var ds = (HikariDataSource) AiDocAssistApplicationAccess.dataSource(tmp.resolve("old.db").toString())) {
            var db = JdbcClient.create(ds);
            // The database as Phase 0/1 left it: 001 applied and recorded.
            try (var c = ds.getConnection(); var st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE schema_migrations (name TEXT PRIMARY KEY, applied_at TEXT NOT NULL)");
                st.executeUpdate(new ClassPathResource("db/001_schema.sql").getContentAsString(StandardCharsets.UTF_8));
                st.executeUpdate("INSERT INTO schema_migrations VALUES ('001_schema.sql', '2026-10-13T00:00:00.000Z')");
            }
            db.sql("""
                    INSERT INTO users (id, email, name, password_hash, is_admin, active, deleted_at) VALUES
                      ('u1', 'admin@aidocassist.local', 'Administrator', 'h1', 1, 1, NULL),
                      ('u2', 'Mei.Tan@startinpoint.com', 'Mei Tan', 'h2', 0, 1, NULL),
                      ('u3', 'mei.tan@other.test', 'Mei Tan (other)', 'h3', 0, 0, '2026-10-12T00:00:00.000Z')""").update();
            db.sql("INSERT INTO groups (id, name) VALUES ('g1', 'general')").update();
            db.sql("INSERT INTO user_groups VALUES ('u2', 'g1')").update();
            db.sql("INSERT INTO threads (id, user_id, title, created_at, updated_at) VALUES ('t1', 'u3', 'old thread', 'x', 'x')").update();
            db.sql("INSERT INTO sync_sources (id, source, name, config) VALUES ('s1', 'filesystem', 'Docs', '{}')").update();
            db.sql("INSERT INTO sync_runs (id, sync_source_id, triggered_by, started_at, stop_requested_by) VALUES ('r1', 's1', 'u1', 'x', 'u1')").update();

            Migrator.migrate(ds);

            assertEquals(List.of("u1 admin admin@aidocassist.local h1 1", "u2 mei.tan Mei.Tan@startinpoint.com h2 0",
                            "u3 mei.tan-3 mei.tan@other.test h3 0"),
                    db.sql("SELECT id, username, email, password_hash, is_admin FROM users ORDER BY id")
                            .query((rs, i) -> rs.getString(1) + " " + rs.getString(2) + " " + rs.getString(3) + " " + rs.getString(4) + " " + rs.getInt(5))
                            .list(), "username = part of the email before @, a repeat gets -<n>; passwords and flags kept");
            assertNotNull(db.sql("SELECT deleted_at FROM users WHERE id = 'u3'").query(String.class).single(), "deleted users stay deleted");
            assertEquals("mei.tan", db.sql("SELECT u.username FROM user_groups ug JOIN users u ON u.id = ug.user_id").query(String.class).single());
            assertEquals("mei.tan-3", db.sql("SELECT u.username FROM threads t JOIN users u ON u.id = t.user_id").query(String.class).single());
            assertEquals("admin", db.sql("SELECT u.username FROM sync_runs r JOIN users u ON u.id = r.triggered_by AND u.id = r.stop_requested_by").query(String.class).single());
            assertEquals(List.of(), db.sql("PRAGMA foreign_key_check").query().listOfRows(), "every reference resolves");

            // The new rules hold: email optional, usernames unique regardless of case, references still enforced.
            db.sql("INSERT INTO users (id, username, name, password_hash) VALUES ('u4', 'no.mail', 'No Mail', 'h')").update();
            assertThrows(Exception.class, () -> db.sql("INSERT INTO users (id, username, name, password_hash) VALUES ('u5', 'ADMIN', 'x', 'h')").update());
            assertThrows(Exception.class, () -> db.sql("INSERT INTO user_groups VALUES ('nobody', 'g1')").update());
        }
    }
}
