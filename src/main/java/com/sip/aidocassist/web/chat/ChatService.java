package com.sip.aidocassist.web.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sip.aidocassist.generation.AnswerService;
import com.sip.aidocassist.generation.Reformer.Exchange;
import com.sip.aidocassist.ingestion.SyncService;
import com.sip.aidocassist.retrieval.Visible;
import com.sip.aidocassist.web.admin.SyncController;
import com.sip.aidocassist.web.auth.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Threads and turns (port of SKB skb/api/chat.py). Threads are private: every query carries
 * user_id = the current user; someone else's thread is treated as not existing.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    public static final int MAX_QUESTION = 1000;
    static final int SIDEBAR_THREADS = 30;
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final Pattern MARKER = Pattern.compile("\\[(E\\d+)\\]");
    private static final Pattern TABLE_RULE = Pattern.compile("^\\|?[\\s:|-]+\\|?$"); // | --- | --- |
    static final Map<String, String> SOURCE_LABEL = Map.of("filesystem", "FILESYSTEM", "upload", "UPLOAD");
    static final Map<String, String> SOURCE_NAME = Map.of("filesystem", "Filesystem", "upload", "Upload");
    static final Map<String, String> SOURCE_COLOR = Map.of("filesystem", "#1F4A3E", "upload", "#5A6270"); // CLAUDE.md "Sync sources"

    public record ThreadRow(String id, String title, String ago, int turns) {}

    public record ThreadRef(String id, String title) {}

    /** A cited evidence item as the panel shows it; available = still visible to this user now. */
    public record EvidenceView(String label, String dom, boolean available, String excerpt, String source, String badge,
                               String name, String color, String title, String shortTitle, String file, String url, String updated) {}

    /** A retrieved-but-not-cited item, shown only while still visible to this user. */
    public record SuggestionView(int rank, String shortTitle, String badge, String name, String url, String source,
                                 String snippet, String where, String updated) {}

    /** One message of a thread, as the page needs it. Assistant fields are null/empty on a user turn. */
    public record TurnView(String role, String id, String text, String html, boolean abstained, List<EvidenceView> evidence,
                           List<SuggestionView> suggested, String reformed, String time, String query, Integer retrieved, boolean last) {}

    private final JdbcClient db;
    private final AnswerService answers;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public ChatService(JdbcClient db, AnswerService answers, TransactionTemplate tx, ObjectMapper json) {
        this.db = db;
        this.answers = answers;
        this.tx = tx;
        this.json = json;
    }

    public List<ThreadRow> threads(User user) {
        return db.sql("""
                        SELECT t.id, t.title, t.updated_at, (SELECT count(*) FROM messages m WHERE m.thread_id = t.id AND m.role = 'user') AS turns
                        FROM threads t WHERE t.user_id = ? ORDER BY t.updated_at DESC LIMIT ?""")
                .params(user.id(), SIDEBAR_THREADS)
                .query((rs, i) -> new ThreadRow(rs.getString(1), rs.getString(2) == null ? "Untitled" : rs.getString(2),
                        SyncController.ago(rs.getString(3)), rs.getInt(4)))
                .list();
    }

    /** The thread if it is this user's, else null (someone else's thread or none: never say which). */
    public ThreadRef owned(User user, String threadId) {
        if (threadId == null || threadId.isBlank()) return null;
        return db.sql("SELECT id, title FROM threads WHERE id = ? AND user_id = ?").params(threadId, user.id())
                .query((rs, i) -> new ThreadRef(rs.getString(1), rs.getString(2))).optional().orElse(null);
    }

    /** The thread's header ⋮ -> Rename. An empty title keeps the old one. */
    public void rename(User user, String threadId, String title) {
        String t = title == null ? "" : title.replaceAll("\\s+", " ").strip();
        if (t.length() > 80) t = t.substring(0, 80);
        if (!t.isEmpty()) db.sql("UPDATE threads SET title = ? WHERE id = ? AND user_id = ?").params(t, threadId, user.id()).update();
    }

    /** Owner only; removed for good, messages first (they reference the thread). False if it isn't theirs. */
    public boolean delete(User user, String threadId) {
        if (owned(user, threadId) == null) return false;
        Integer n = tx.execute(s -> {
            int m = db.sql("DELETE FROM messages WHERE thread_id = ?").param(threadId).update();
            db.sql("DELETE FROM threads WHERE id = ? AND user_id = ?").params(threadId, user.id()).update();
            return m;
        });
        log.info("thread deleted user={} thread={} messages={}", user.id(), threadId, n);
        return true;
    }

    static String titleFrom(String question) {
        String t = question.replaceAll("\\s+", " ").strip();
        return t.length() <= 80 ? t : t.substring(0, 79).stripTrailing() + "…";
    }

    /**
     * One chat turn: answer with the thread's earlier turns as context (Phase 5), then save the
     * question and the answer (a new thread if {@code thread} is null). Returns the thread id. If the
     * model server can't be reached the exception propagates and nothing is saved.
     */
    public String ask(User user, String question, ThreadRef thread) throws Exception {
        List<Exchange> history = new ArrayList<>();
        if (thread != null) {
            String[] pendingQ = {null};
            db.sql("SELECT role, content FROM messages WHERE thread_id = ? ORDER BY created_at, id").param(thread.id())
                    .query(rs -> {
                        if (rs.getString(1).equals("user")) pendingQ[0] = rs.getString(2);
                        else if (pendingQ[0] != null) { history.add(new Exchange(pendingQ[0], rs.getString(2))); pendingQ[0] = null; }
                    });
        }
        AnswerService.Turn turn = answers.answer(question, user.groups(), history);
        String citations = json.writeValueAsString(turn.citations().stream()
                .map(c -> Map.of("label", c.label(), "source", c.source(), "source_id", c.sourceId(), "excerpt", c.excerpt())).toList());
        String suggested = json.writeValueAsString(turn.suggested().stream()
                .map(s -> Map.of("source", s.source(), "source_id", s.sourceId(), "rank", s.rank(), "score", s.score())).toList());
        Instant now = Instant.now();
        return tx.execute(s -> {
            String tid = thread != null ? thread.id() : UUID.randomUUID().toString();
            if (thread == null)
                db.sql("INSERT INTO threads (id, user_id, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?)")
                        .params(tid, user.id(), titleFrom(question), SyncService.time(now), SyncService.time(now)).update();
            db.sql("INSERT INTO messages (id, thread_id, role, content, reformed_query, created_at) VALUES (?, ?, 'user', ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), tid, question, turn.reformedQuery(), SyncService.time(now)).update();
            // One millisecond later, so the answer always sorts after its question.
            db.sql("INSERT INTO messages (id, thread_id, role, content, citations, suggested, retrieved, abstained, created_at) "
                            + "VALUES (?, ?, 'assistant', ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), tid, turn.answer(), citations, suggested, turn.retrieved(), turn.abstained(),
                            SyncService.time(now.plusMillis(1))).update();
            db.sql("UPDATE threads SET updated_at = ? WHERE id = ?").params(SyncService.time(now), tid).update();
            return tid;
        });
    }

    /**
     * The thread's messages for display. Citation facts are looked up from knowledge_records now,
     * never taken from the model, and re-checked against the user's CURRENT groups and enabled entries:
     * evidence they lost access to, that left the index, or whose entry was disabled shows as unavailable.
     */
    public List<TurnView> turns(User user, String threadId) throws Exception {
        record Msg(String id, String role, String content, String reformed, String citations, boolean abstained, String created,
                   String suggested, Integer retrieved) {}
        List<Msg> msgs = db.sql("SELECT id, role, content, reformed_query, citations, abstained, created_at, suggested, retrieved "
                        + "FROM messages WHERE thread_id = ? ORDER BY created_at, id").param(threadId)
                .query((rs, i) -> new Msg(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getInt(6) == 1, rs.getString(7), rs.getString(8), (Integer) rs.getObject(9))).list();
        TypeReference<List<Map<String, Object>>> listOfMaps = new TypeReference<>() {};
        Set<String> ids = new LinkedHashSet<>();
        Map<String, List<Map<String, Object>>> cites = new HashMap<>(), sugg = new HashMap<>();
        for (Msg m : msgs) {
            if (!m.role().equals("assistant")) continue;
            cites.put(m.id(), json.readValue(m.citations(), listOfMaps));
            sugg.put(m.id(), json.readValue(m.suggested(), listOfMaps));
            cites.get(m.id()).forEach(c -> ids.add(String.valueOf(c.get("source_id"))));
            sugg.get(m.id()).forEach(c -> ids.add(String.valueOf(c.get("source_id"))));
        }
        Map<String, Map<String, Object>> records = visibleRecords(new ArrayList<>(ids), user.groups());

        List<TurnView> out = new ArrayList<>();
        String pendingReform = null, asked = "";
        for (int idx = 0; idx < msgs.size(); idx++) {
            Msg m = msgs.get(idx);
            String time = HHMM.format(Instant.parse(m.created()));
            boolean last = idx == msgs.size() - 1;
            if (m.role().equals("user")) {
                out.add(new TurnView("user", m.id(), m.content(), null, false, List.of(), List.of(), null, time, null, null, last));
                pendingReform = m.reformed();
                asked = m.reformed() != null ? m.reformed() : m.content();
                continue;
            }
            List<EvidenceView> evidence = new ArrayList<>();
            for (Map<String, Object> c : cites.get(m.id())) {
                String sid = String.valueOf(c.get("source_id"));
                String label = String.valueOf(c.get("label"));
                Map<String, Object> r = records.get(sid);
                String source = r != null ? (String) r.get("source") : String.valueOf(c.get("source"));
                String title = r != null && r.get("title") != null ? (String) r.get("title") : "";
                evidence.add(new EvidenceView(label, "ev-" + m.id() + "-" + label, r != null, String.valueOf(c.getOrDefault("excerpt", "")),
                        source, SOURCE_LABEL.getOrDefault(source, "SOURCE"), SOURCE_NAME.getOrDefault(source, "source"),
                        SOURCE_COLOR.getOrDefault(source, "#948E80"), title,
                        r != null ? lastLevel(title) : sid.substring(sid.lastIndexOf('/') + 1),
                        r != null ? (String) r.get("path") : "", r != null ? link((String) r.get("source_url")) : null,
                        r != null ? SyncController.ago((String) r.get("updated_at")) : ""));
            }
            // Suggested (not cited): shown only while still accessible; no "unavailable" row, it was never evidence.
            List<SuggestionView> suggested = new ArrayList<>();
            for (Map<String, Object> s : sugg.get(m.id())) {
                Map<String, Object> r = records.get(String.valueOf(s.get("source_id")));
                if (r == null) continue;
                String source = (String) r.get("source"), title = r.get("title") == null ? "" : (String) r.get("title");
                suggested.add(new SuggestionView(((Number) s.get("rank")).intValue(), lastLevel(title.isEmpty() ? (String) r.get("source_id") : title),
                        SOURCE_LABEL.getOrDefault(source, "SOURCE"), SOURCE_NAME.getOrDefault(source, "source"), link((String) r.get("source_url")),
                        source, snippet((String) r.get("content")), where(title), SyncController.ago((String) r.get("updated_at"))));
            }
            out.add(new TurnView("assistant", m.id(), m.content(), renderAnswer(m.content(), m.id()), m.abstained(), evidence, suggested,
                    pendingReform, time, asked, m.retrieved(), last));
            pendingReform = null;
        }
        return out;
    }

    /** source_id -> the record's display facts, only for records this user may see now. */
    private Map<String, Map<String, Object>> visibleRecords(List<String> sourceIds, List<String> groups) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (sourceIds.isEmpty() || groups.isEmpty()) return out;
        String in = String.join(",", sourceIds.stream().map(x -> "?").toList());
        db.sql("SELECT k.source_id, k.source, k.title, k.source_url, k.updated_at, json_extract(k.metadata, '$.path') AS path, "
                        + "substr(k.content, 1, 1500) AS content FROM knowledge_records k WHERE k.source_id IN (" + in + ") AND " + Visible.where(groups))
                .params(Visible.args(sourceIds, groups, List.of()))
                .query(rs -> {
                    Map<String, Object> r = new HashMap<>();
                    for (String col : List.of("source_id", "source", "title", "source_url", "updated_at", "path", "content")) r.put(col, rs.getString(col));
                    out.put(rs.getString("source_id"), r);
                });
        return out;
    }

    /** Escape the model's text, THEN turn [E1] markers into citation chips that open the evidence panel. */
    static String renderAnswer(String text, String msgId) {
        String html = HtmlUtils.htmlEscape(text).replace("\n", "<br>");
        Matcher m = MARKER.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find())
            m.appendReplacement(sb, "<a class=\"cite\" href=\"#ev-" + msgId + "-" + m.group(1) + "\" data-ev=\"ev-" + msgId + "-" + m.group(1)
                    + "\" aria-label=\"Evidence " + m.group(1) + "\">" + m.group(1) + "</a>");
        m.appendTail(sb);
        return sb.toString();
    }

    /** A link the browser can open: a web link, or the app's own access-checked download route. A file:// path isn't one. */
    static String link(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("/files/")) ? url : null;
    }

    private static String lastLevel(String title) {
        String[] parts = title.split(" > ");
        return parts[parts.length - 1];
    }

    /** Where a suggestion lives: "Document › section path" (the title without its last level). */
    static String where(String title) {
        String[] parts = (title == null ? "" : title).split(" > ");
        return String.join(" › ", java.util.Arrays.asList(parts).subList(0, Math.max(0, parts.length - 1)));
    }

    /**
     * A readable taste of a record for Suggested: table header/rule rows dropped, cells joined by
     * " · " (a header row alone says nothing), leading symbols stripped, cut to 240 characters.
     */
    static String snippet(String content) {
        List<String> parts = new ArrayList<>();
        boolean headerSeen = false;
        for (String line : (content == null ? "" : content).split("\n")) {
            line = line.strip();
            if (line.isEmpty() || TABLE_RULE.matcher(line).matches()) continue;
            if (line.startsWith("|")) {
                if (!headerSeen) { headerSeen = true; continue; } // the first row of a table is its header
                List<String> cells = new ArrayList<>();
                for (String c : line.replaceAll("^\\||\\|$", "").split("\\|")) if (!c.isBlank()) cells.add(c.strip());
                line = String.join(" · ", cells);
            }
            parts.add(line);
        }
        String text = String.join(" ", parts).replaceAll("\\s+", " ").replaceAll("^[^A-Za-z0-9(\"'\\[]+", "").strip();
        return text.length() <= 240 ? text : text.substring(0, 239).stripTrailing() + "…";
    }
}
