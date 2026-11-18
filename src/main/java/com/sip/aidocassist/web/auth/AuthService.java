package com.sip.aidocassist.web.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Port of SKB skb/api/auth.py, with username + password instead of LDAP (CLAUDE.md "Access control").
 * {@link #authenticate} is the one place credentials are checked; sessions, the admin gate and
 * everything that asks "who is the current user" go through {@link #load}.
 */
@Service
public class AuthService {

    public static final int MIN_PASSWORD_LEN = 10;
    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(15);

    // ponytail: in-memory lockout (as SKB); resets on restart. One process here, so it is never split
    // between workers. Move to a table if the app ever runs as several processes.
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    private final JdbcClient db;
    private final BCryptPasswordEncoder encoder;
    private final String dummyHash;

    public AuthService(JdbcClient db, BCryptPasswordEncoder encoder) {
        this.db = db;
        this.encoder = encoder;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    /** " JDoe " and "jdoe" are the same user: usernames are trimmed and lower-cased everywhere. */
    public static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    /** Null when the password is acceptable, else the message to show. */
    public static String passwordProblem(String password) {
        if (password.length() < MIN_PASSWORD_LEN) return "Use at least " + MIN_PASSWORD_LEN + " characters.";
        // BCrypt only looks at the first 72 bytes; refuse rather than silently ignore the rest.
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) return "Use at most 72 characters.";
        return null;
    }

    public boolean lockedOut(String username) {
        Deque<Instant> recent = failures.get(normalize(username));
        if (recent == null) return false;
        synchronized (recent) {
            Instant cutoff = Instant.now().minus(LOCK);
            while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) recent.pollFirst();
            return recent.size() >= MAX_FAILURES;
        }
    }

    /**
     * The user, or null for every failure (unknown username, wrong password, inactive or deleted):
     * callers can't tell which. Failures count towards the lockout; a success clears it.
     */
    public User authenticate(String username, String password) {
        String key = normalize(username);
        var row = db.sql("SELECT id, password_hash, active, deleted_at FROM users WHERE username = ?")
                .param(key).query().listOfRows().stream().findFirst();
        boolean ok;
        if (row.isEmpty()) {
            encoder.matches(password, dummyHash); // same time as a real check: no hint that the username doesn't exist
            ok = false;
        } else {
            var r = row.get();
            ok = encoder.matches(password, (String) r.get("password_hash"))
                    && ((Number) r.get("active")).intValue() == 1 && r.get("deleted_at") == null;
        }
        if (!ok) {
            Deque<Instant> recent = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (recent) { recent.addLast(Instant.now()); }
            return null;
        }
        failures.remove(key);
        return load((String) row.get().get("id"));
    }

    /** Fresh copy of an active, not-deleted user, or null. */
    public User load(String id) {
        return db.sql("""
                        SELECT u.id, u.username, u.email, u.name, u.is_admin, u.password_hash,
                               (SELECT group_concat(name, ',') FROM (SELECT g.name FROM user_groups ug
                                   JOIN groups g ON g.id = ug.group_id WHERE ug.user_id = u.id ORDER BY g.name)) AS groups
                        FROM users u WHERE u.id = ? AND u.active = 1 AND u.deleted_at IS NULL""")
                .param(id)
                .query((rs, i) -> new User(rs.getString("id"), rs.getString("username"), rs.getString("email"), rs.getString("name"),
                        rs.getInt("is_admin") == 1, splitNames(rs.getString("groups")),
                        fingerprint(rs.getString("password_hash"))))
                .optional().orElse(null);
    }

    /** Null on success, else the message to show. */
    public String changePassword(User user, String current, String next, String confirm) {
        if (!next.equals(confirm)) return "The new passwords don't match.";
        String problem = passwordProblem(next);
        if (problem != null) return problem;
        if (next.equals(current)) return "The new password must differ from the current one.";
        if (authenticate(user.username(), current) == null) return "Your current password is incorrect.";
        db.sql("UPDATE users SET password_hash = ? WHERE id = ?").params(encoder.encode(next), user.id()).update();
        return null;
    }

    public String hash(String password) {
        return encoder.encode(password);
    }

    static List<String> splitNames(String csv) {
        return csv == null || csv.isEmpty() ? List.of() : Arrays.asList(csv.split(","));
    }

    static String fingerprint(String passwordHash) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(passwordHash.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
