package com.sip.aidocassist.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Port of SKB skb/db/seed.py. Loads groups, users, user_groups and sync_sources from SKB's
 * init-data/*.csv. Safe to re-run: groups and user_groups insert-if-missing; users update
 * name/is_admin/active (password only set on first insert, so a re-run never resets one);
 * sync_sources matched on (source, name). One transaction: any bad row aborts the whole seed.
 *
 * users.csv is SKB's shape (username, name, is_admin, active) plus {@code initial_password}
 * (required for a new user: no LDAP here) and an optional {@code email}. Only filesystem
 * sync_sources are kept.
 */
@Component
public class Seeder {

    private final JdbcClient db;
    private final BCryptPasswordEncoder encoder;
    private final ObjectMapper json;

    public Seeder(JdbcClient db, BCryptPasswordEncoder encoder, ObjectMapper json) {
        this.db = db;
        this.encoder = encoder;
        this.json = json;
    }

    @Transactional
    public void seed(Path dir) throws Exception {
        for (var r : Csv.read(dir.resolve("groups.csv")))
            db.sql("INSERT INTO groups (id, name) VALUES (?, ?) ON CONFLICT (name) DO NOTHING")
                    .params(uuid(), r.get("name").trim()).update();
        Map<String, String> groupIds = idsBy("SELECT name, id FROM groups");

        for (var r : Csv.read(dir.resolve("users.csv"))) {
            String username = required(r, "username", "users.csv").toLowerCase();
            String email = r.getOrDefault("email", "").trim().toLowerCase();
            boolean admin = flag(r.get("is_admin")), active = flag(r.get("active"));
            int updated = db.sql("UPDATE users SET name = ?, is_admin = ?, active = ? WHERE username = ?")
                    .params(r.get("name").trim(), admin, active, username).update();
            if (updated == 0)
                db.sql("INSERT INTO users (id, username, email, name, password_hash, is_admin, active) VALUES (?, ?, ?, ?, ?, ?, ?)")
                        .params(uuid(), username, email.isEmpty() ? null : email, r.get("name").trim(),
                                encoder.encode(required(r, "initial_password", "users.csv " + username)), admin, active)
                        .update();
        }
        Map<String, String> userIds = idsBy("SELECT lower(username), id FROM users");

        for (var r : Csv.read(dir.resolve("user_groups.csv"))) {
            String username = r.get("username").trim().toLowerCase();
            String group = r.get("group_name").trim();
            if (!userIds.containsKey(username) || !groupIds.containsKey(group))
                throw new IllegalArgumentException("user_groups.csv: unknown user '" + username + "' or group '" + group + "'");
            db.sql("INSERT INTO user_groups (user_id, group_id) VALUES (?, ?) ON CONFLICT DO NOTHING")
                    .params(userIds.get(username), groupIds.get(group)).update();
        }

        for (var r : Csv.read(dir.resolve("sync_sources.csv"))) {
            String source = r.get("source").trim(), name = r.get("name").trim();
            if (!source.equals("filesystem")) {
                System.out.println("skipped sync source '" + name + "': source '" + source + "' is not supported here");
                continue;
            }
            List<String> tags = Arrays.stream(r.get("default_group_ids").split(";")).map(String::trim)
                    .filter(s -> !s.isEmpty()).toList();
            // Tags are stored as names, so a typo fails no FK; it would silently hide every record
            // from this entry from everyone. Check them here instead (same as SKB).
            List<String> unknown = tags.stream().filter(t -> !groupIds.containsKey(t)).toList();
            if (tags.isEmpty() || !unknown.isEmpty())
                throw new IllegalArgumentException("sync_sources.csv '" + name + "': missing/unknown groups "
                        + (unknown.isEmpty() ? tags : unknown));
            boolean exists = db.sql("SELECT count(*) FROM sync_sources WHERE source = ? AND name = ? AND deleted_at IS NULL")
                    .params(source, name).query(Integer.class).single() > 0;
            if (!exists)
                db.sql("INSERT INTO sync_sources (id, source, name, config, default_group_ids, enabled, sync_interval_minutes) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 1440)") // new filesystem entries default to every 24 h
                        .params(uuid(), source, name, json.writeValueAsString(json.readTree(r.get("config"))),
                                json.writeValueAsString(tags), flag(r.get("enabled")))
                        .update();
        }

        for (String t : List.of("groups", "users", "user_groups", "sync_sources"))
            System.out.println(t + " " + db.sql("SELECT count(*) FROM " + t).query(Integer.class).single());
    }

    private Map<String, String> idsBy(String sql) {
        return db.sql(sql).query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2))).list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static String required(Map<String, String> r, String col, String where) {
        String v = r.get(col);
        if (v == null || v.isBlank()) throw new IllegalArgumentException(where + ": '" + col + "' is required");
        return v.trim();
    }

    static boolean flag(String value) {
        String v = value == null ? "" : value.trim().toLowerCase();
        if (!v.equals("true") && !v.equals("false")) throw new IllegalArgumentException("expected true/false, got '" + value + "'");
        return v.equals("true");
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
