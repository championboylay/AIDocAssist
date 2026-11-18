package com.sip.aidocassist.web.admin;

import com.sip.aidocassist.db.Csv;
import com.sip.aidocassist.web.auth.AuthService;
import com.sip.aidocassist.web.auth.User;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Users: data, validation and CSV import (port of SKB skb/api/admin_users.py). People log in with
 * a username and a password the admin sets here; there is no directory to check against. Email is
 * optional, a contact address only.
 */
@Service
public class UserAdmin {

    /** SKB's username rule: 1-64 of a-z 0-9 . _ $ - (after trimming and lower-casing). */
    static final Pattern USERNAME = Pattern.compile("^[a-z0-9._$-]{1,64}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Set<String> TRUE = Set.of("true", "yes", "y", "1"), FALSE = Set.of("false", "no", "n", "0");

    /** @param email null when not given */
    public record Row(String id, String username, String email, String name, boolean isAdmin, boolean active, List<String> groups) {}

    /** @param email "" when not given */
    public record Form(String username, String email, String name, String password, List<String> groups, boolean isAdmin, boolean active) {}

    /** One row of an import preview. {@code password} is only ever written, never shown. */
    public record Change(String action, String username, String email, String id, String name, String password, List<String> groups,
                         boolean isAdmin, boolean active, List<String> details) {}

    public record Import(List<Change> changes, List<String> errors) {}

    private final JdbcClient db;
    private final AuthService auth;

    public UserAdmin(JdbcClient db, AuthService auth) {
        this.db = db;
        this.auth = auth;
    }

    // ---------- data ----------

    public List<String> allGroups() {
        return db.sql("SELECT name FROM groups ORDER BY name").query(String.class).list();
    }

    public int[] counts() {
        return db.sql("SELECT count(*), coalesce(sum(active), 0) FROM users WHERE deleted_at IS NULL")
                .query((rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)}).single();
    }

    public List<Row> list(int limit, int offset) {
        return db.sql("""
                        SELECT u.id, u.username, u.email, u.name, u.is_admin, u.active,
                               (SELECT group_concat(name, ',') FROM (SELECT g.name FROM user_groups ug
                                   JOIN groups g ON g.id = ug.group_id WHERE ug.user_id = u.id ORDER BY g.name)) AS groups
                        FROM users u WHERE u.deleted_at IS NULL
                        ORDER BY u.active DESC, lower(u.name), u.id LIMIT ? OFFSET ?""")
                .params(limit, offset)
                .query((rs, i) -> new Row(rs.getString("id"), rs.getString("username"), rs.getString("email"), rs.getString("name"),
                        rs.getInt("is_admin") == 1, rs.getInt("active") == 1, names(rs.getString("groups"))))
                .list();
    }

    public Row get(String id) {
        return list(Integer.MAX_VALUE, 0).stream().filter(u -> u.id().equals(id)).findFirst().orElse(null);
    }

    private Map<String, Row> byUsername() {
        Map<String, Row> m = new HashMap<>();
        for (Row r : list(Integer.MAX_VALUE, 0)) m.put(r.username(), r);
        return m;
    }

    /** Another live user has this username. (A deleted user's username is free: adding it brings them back.) */
    public boolean usernameTaken(String username, String exceptId) {
        return db.sql("SELECT count(*) FROM users WHERE username = ? AND id <> ? AND deleted_at IS NULL")
                .params(username, exceptId == null ? "" : exceptId).query(Integer.class).single() > 0;
    }

    /**
     * Another user (live or deleted: the row keeps it) has this email. The row being edited, and a
     * deleted user with the same username (who is brought back), don't count.
     */
    public boolean emailTaken(String email, String exceptId, String username) {
        if (email.isEmpty()) return false;
        return db.sql("SELECT count(*) FROM users WHERE email = ? AND id <> ? AND username <> ?")
                .params(email, exceptId == null ? "" : exceptId, username).query(Integer.class).single() > 0;
    }

    private static String nullIfEmpty(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /**
     * Insert, or bring back a deleted user with that username (their old threads come back with
     * them). Never overwrites a live user: returns null then.
     */
    private String add(String username, String email, String name, String passwordHash, boolean isAdmin, boolean active) {
        return db.sql("""
                        INSERT INTO users (id, username, email, name, password_hash, is_admin, active) VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (username) DO UPDATE SET email = excluded.email, name = excluded.name,
                            password_hash = excluded.password_hash, is_admin = excluded.is_admin, active = excluded.active,
                            deleted_at = NULL
                        WHERE users.deleted_at IS NOT NULL
                        RETURNING id""")
                .params(UUID.randomUUID().toString(), username, nullIfEmpty(email), name, passwordHash, isAdmin, active)
                .query(String.class).optional().orElse(null);
    }

    private void setGroups(String userId, List<String> groups) {
        db.sql("DELETE FROM user_groups WHERE user_id = ?").param(userId).update();
        for (String g : groups)
            db.sql("INSERT INTO user_groups (user_id, group_id) SELECT ?, id FROM groups WHERE name = ?")
                    .params(userId, g).update();
    }

    @Transactional
    public void create(Form f) {
        String id = add(f.username(), f.email(), f.name(), auth.hash(f.password()), f.isAdmin(), f.active());
        if (id == null) throw new IllegalStateException("username taken: " + f.username());
        setGroups(id, f.groups());
    }

    /** A blank password keeps the current one; setting one logs that user out everywhere. */
    @Transactional
    public void update(String id, Form f) {
        db.sql("UPDATE users SET username = ?, email = ?, name = ?, is_admin = ?, active = ? WHERE id = ?")
                .params(f.username(), nullIfEmpty(f.email()), f.name(), f.isAdmin(), f.active(), id).update();
        if (!f.password().isEmpty())
            db.sql("UPDATE users SET password_hash = ? WHERE id = ?").params(auth.hash(f.password()), id).update();
        setGroups(id, f.groups());
    }

    /**
     * Off the roster, out of their groups, can't log in. The row stays (marked deleted) so their
     * threads and their name in the sync history are kept. Adding the same username brings them back.
     */
    @Transactional
    public void delete(String id) {
        db.sql("DELETE FROM user_groups WHERE user_id = ?").param(id).update();
        db.sql("UPDATE users SET active = 0, deleted_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = ?").param(id).update();
    }

    // ---------- validation ----------

    public List<String> validate(Form f, List<String> groupsKnown, boolean isNew, boolean isSelf, boolean usernameTaken, boolean emailTaken) {
        List<String> errors = new ArrayList<>();
        if (!USERNAME.matcher(f.username()).matches())
            errors.add("Choose a username: 1–64 characters, lowercase letters, digits and . _ $ - (e.g. jdoe).");
        else if (usernameTaken) errors.add("Another user already has that username.");
        if (!f.email().isEmpty()) {
            if (!EMAIL.matcher(f.email()).matches() || f.email().length() > 254) errors.add("Enter a valid email address, or leave it empty.");
            else if (emailTaken) errors.add("Another user (or a deleted one) already has that email.");
        }
        if (f.name().isEmpty()) errors.add("Display name is required.");
        if (isNew && f.password().isEmpty()) errors.add("Set an initial password.");
        else if (!f.password().isEmpty()) {
            String problem = AuthService.passwordProblem(f.password());
            if (problem != null) errors.add("Password: " + problem);
        }
        List<String> unknown = f.groups().stream().filter(g -> !groupsKnown.contains(g)).toList();
        if (!unknown.isEmpty()) errors.add("Unknown group(s): " + String.join(", ", unknown) + ".");
        if (isSelf && !f.isAdmin()) errors.add("You can't remove your own admin rights (another admin can).");
        if (isSelf && !f.active()) errors.add("You can't deactivate your own account.");
        return errors;
    }

    // ---------- CSV import: upload -> preview -> confirm ----------

    /**
     * Validate the whole file against the current database; nothing is written. Columns: username,
     * groups (required), name, email, is_admin, active, initial_password (optional), SKB's users.csv
     * shape plus email and initial_password. A blank cell (except groups) leaves that field unchanged
     * for existing users. New users need a name and an initial_password; for an existing user a
     * non-blank initial_password sets a new one.
     */
    public Import parseImport(String text, User me) {
        List<String> groupsKnown = allGroups();
        Map<String, Row> existing = byUsername();
        List<List<String>> rows = Csv.parse(text);
        List<String> header = rows.isEmpty() ? List.of() : rows.get(0).stream().map(h -> h.trim().toLowerCase()).toList();
        if (!header.containsAll(List.of("username", "groups")))
            return new Import(List.of(), List.of("The file needs at least the columns username and groups (first row = column names)."));
        List<Change> changes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>(), seenEmails = new HashSet<>();

        for (int i = 1; i < rows.size(); i++) {
            int rowNo = i + 1;
            List<String> raw = rows.get(i);
            if (raw.size() == 1 && raw.get(0).isBlank()) continue;
            if (raw.size() != header.size()) {
                errors.add("Row " + rowNo + ": expected " + header.size() + " fields, found " + raw.size() + ".");
                continue;
            }
            Map<String, String> r = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) r.put(header.get(c), raw.get(c).trim());
            String username = AuthService.normalize(r.get("username"));
            if (username.isEmpty()) continue;
            if (!USERNAME.matcher(username).matches()) { errors.add("Row " + rowNo + ": '" + username + "' is not a valid username."); continue; }
            if (!seen.add(username)) { errors.add("Row " + rowNo + ": " + username + " appears more than once."); continue; }

            List<String> groups = new ArrayList<>(new TreeSet<>(names(r.get("groups").replace(';', ','))));
            List<String> unknown = groups.stream().filter(g -> !groupsKnown.contains(g)).toList();
            if (!unknown.isEmpty())
                errors.add("Row " + rowNo + ": unknown group(s) " + String.join(", ", unknown)
                        + " (known: " + String.join(", ", groupsKnown) + ").");
            Boolean isAdmin = flag(r.get("is_admin"), rowNo, "is_admin", errors);
            Boolean active = flag(r.get("active"), rowNo, "active", errors);
            String name = r.getOrDefault("name", "");
            String email = AuthService.normalize(r.getOrDefault("email", ""));
            String password = r.getOrDefault("initial_password", "");
            String problem = password.isEmpty() ? null : AuthService.passwordProblem(password);
            if (problem != null) errors.add("Row " + rowNo + ": initial_password: " + problem);
            Row old = existing.get(username);
            if (!email.isEmpty()) {
                if (!EMAIL.matcher(email).matches()) errors.add("Row " + rowNo + ": '" + email + "' is not a valid email.");
                else if (!seenEmails.add(email) || emailTaken(email, old == null ? null : old.id(), username))
                    errors.add("Row " + rowNo + ": the email " + email + " already belongs to another user.");
            }

            if (old == null) {
                if (name.isEmpty()) errors.add("Row " + rowNo + ": a new user needs a name.");
                if (password.isEmpty()) errors.add("Row " + rowNo + ": a new user needs an initial_password.");
                List<String> details = new ArrayList<>(List.of("groups: " + (groups.isEmpty() ? "(none)" : String.join(", ", groups))));
                if (!email.isEmpty()) details.add("email: " + email);
                if (Boolean.TRUE.equals(isAdmin)) details.add("admin");
                if (Boolean.FALSE.equals(active)) details.add("inactive");
                changes.add(new Change("add", username, email, null, name, password, groups, Boolean.TRUE.equals(isAdmin),
                        active == null || active, details));
                continue;
            }
            String newName = name.isEmpty() ? old.name() : name;
            String newEmail = email.isEmpty() ? Objects.toString(old.email(), "") : email;
            boolean newAdmin = isAdmin == null ? old.isAdmin() : isAdmin;
            boolean newActive = active == null ? old.active() : active;
            if (old.id().equals(me.id()) && (!newAdmin || !newActive))
                errors.add("Row " + rowNo + ": you can't remove your own admin rights or deactivate yourself.");
            List<String> details = new ArrayList<>();
            if (!newName.equals(old.name())) details.add("name: " + old.name() + " → " + newName);
            if (!newEmail.equals(Objects.toString(old.email(), "")))
                details.add("email: " + (old.email() == null ? "(none)" : old.email()) + " → " + newEmail);
            if (!groups.equals(old.groups()))
                details.add("groups: " + (old.groups().isEmpty() ? "(none)" : String.join(", ", old.groups()))
                        + " → " + (groups.isEmpty() ? "(none)" : String.join(", ", groups)));
            if (newAdmin != old.isAdmin()) details.add(newAdmin ? "made admin" : "admin removed");
            if (newActive != old.active()) details.add(newActive ? "reactivated" : "deactivated");
            if (!password.isEmpty()) details.add("password reset");
            changes.add(new Change(details.isEmpty() ? "unchanged" : "update", username, newEmail, old.id(), newName, password,
                    groups, newAdmin, newActive, details));
        }
        return new Import(changes, errors);
    }

    @Transactional
    public int applyImport(List<Change> changes) {
        int n = 0;
        for (Change c : changes) {
            if (c.action().equals("add")) {
                String id = add(c.username(), c.email(), c.name(), auth.hash(c.password()), c.isAdmin(), c.active());
                if (id == null) throw new IllegalStateException("username taken: " + c.username());
                setGroups(id, c.groups());
                n++;
            } else if (c.action().equals("update")) {
                update(c.id(), new Form(c.username(), c.email(), c.name(), c.password(), c.groups(), c.isAdmin(), c.active()));
                n++;
            }
        }
        return n;
    }

    private static Boolean flag(String value, int rowNo, String field, List<String> errors) {
        String v = value == null ? "" : value.trim().toLowerCase();
        if (TRUE.contains(v)) return true;
        if (FALSE.contains(v)) return false;
        if (!v.isEmpty()) errors.add("Row " + rowNo + ": " + field + " must be true or false, not '" + value + "'.");
        return null;
    }

    private static List<String> names(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
