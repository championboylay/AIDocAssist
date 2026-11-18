package com.sip.aidocassist.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;

/** Applies classpath:db/NNN_*.sql in name order, each once, each in its own transaction. */
public final class Migrator {

    private static final Logger log = LoggerFactory.getLogger(Migrator.class);

    private Migrator() {}

    public static void migrate(DataSource ds) throws Exception {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:db/*.sql");
        Arrays.sort(files, Comparator.comparing(Resource::getFilename));
        try (Connection c = ds.getConnection()) {
            try (var st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS schema_migrations (name TEXT PRIMARY KEY, applied_at TEXT NOT NULL)");
            }
            for (Resource f : files) {
                try (var applied = c.prepareStatement("SELECT 1 FROM schema_migrations WHERE name = ?")) {
                    applied.setString(1, f.getFilename());
                    if (applied.executeQuery().next()) continue;
                }
                apply(c, f);
                log.info("Applied migration {}", f.getFilename());
            }
        }
    }

    /**
     * SQLite's procedure for schema changes: foreign keys off (the setting can't change inside a
     * transaction, so before it starts), the script in one transaction, then PRAGMA foreign_key_check:
     * commit only if no reference is broken. This lets a migration rebuild a table that other tables
     * point at (002_username.sql), and rolls back any migration that would break a reference.
     */
    private static void apply(Connection c, Resource f) throws Exception {
        String sql = f.getContentAsString(StandardCharsets.UTF_8);
        try (var st = c.createStatement()) {
            st.executeUpdate("PRAGMA foreign_keys = OFF");
        }
        c.setAutoCommit(false);
        try {
            // sqlite-jdbc runs every statement in the string (sqlite3_exec), triggers included.
            try (var st = c.createStatement();
                 var mark = c.prepareStatement("INSERT INTO schema_migrations (name, applied_at) VALUES (?, ?)")) {
                st.executeUpdate(sql);
                try (var broken = st.executeQuery("PRAGMA foreign_key_check")) {
                    if (broken.next())
                        throw new SQLException("it would leave a broken reference: " + broken.getString(1) + " row " + broken.getLong(2)
                                + " points at a missing row in " + broken.getString(3));
                }
                mark.setString(1, f.getFilename());
                mark.setString(2, Instant.now().toString());
                mark.executeUpdate();
            }
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw new IllegalStateException("Migration " + f.getFilename() + " failed: " + e.getMessage(), e);
        } finally {
            c.setAutoCommit(true);
            try (var st = c.createStatement()) {
                st.executeUpdate("PRAGMA foreign_keys = ON"); // the pooled connection goes back as the app expects it
            }
        }
    }
}
