package com.sip.aidocassist.web.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sip.aidocassist.chunking.Chunker;
import com.sip.aidocassist.ingestion.SyncQueue;
import com.sip.aidocassist.ingestion.SyncScheduler;
import com.sip.aidocassist.ingestion.SyncService;
import com.sip.aidocassist.sources.UploadStore;
import com.sip.aidocassist.web.auth.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Admin: Sync sources (port of SKB admin_sync.py): Upload and Filesystem, one tab each.
 * One entry = one folder, or one uploaded file, with its own tag(s); picking the tag is the
 * approval. /admin/** is admin-only.
 */
@Controller
@RequestMapping("/admin/sync")
public class SyncController {

    private static final Logger log = LoggerFactory.getLogger(SyncController.class);
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    static final List<String> ACTIONS = List.of("inserted", "updated", "deleted", "skipped");

    /**
     * One entry as the pages show it. records = files with records; skipped = ingest_skips rows.
     * folder = the folder path (filesystem) or "" (upload). fileName/fileKb = an upload's stored file,
     * null when it is missing from UPLOAD_DIR.
     */
    public record Entry(String id, String source, String name, String folder, List<String> tags, boolean enabled,
                        String synced, String status, String error, int records, int skipped, boolean stopping,
                        boolean queued, String deleted, Integer interval, String schedule, String fileName, long fileKb) {
        public boolean busy() { return "syncing".equals(status) || queued; }
        public boolean upload() { return "upload".equals(source); }
        public String fileUrl() { return fileName == null ? null : "/files/" + id + "/" + fileName; }
    }

    /** The Sync page's source tabs, in order (SKB: Upload first and the default). */
    static final List<String> SOURCES = List.of("upload", "filesystem");

    public record Run(String id, String started, String finished, String status, String error, String duration,
                      int inserted, int updated, int deletedCount, int skippedCount, String by, String byUsername, boolean reread) {}

    public record Item(String path, String shortPath, String action, int chunks) {}

    private final JdbcClient db;
    private final ObjectMapper json;
    private final SyncQueue queue;
    private final UploadStore uploads;

    public SyncController(JdbcClient db, ObjectMapper json, SyncQueue queue, UploadStore uploads) {
        this.db = db;
        this.json = json;
        this.queue = queue;
        this.uploads = uploads;
    }

    // ---------- data ----------

    public static String ago(String iso) {
        if (iso == null) return "never";
        long s = Duration.between(Instant.parse(iso), Instant.now()).getSeconds();
        if (s >= 86400) return s / 86400 + "d ago";
        if (s >= 3600) return s / 3600 + "h ago";
        if (s >= 60) return s / 60 + "min ago";
        return "just now";
    }

    static String shown(String iso) {
        return iso == null ? "—" : SHOWN.format(Instant.parse(iso));
    }

    private List<String> tags(String jsonArray) {
        try {
            return json.readValue(jsonArray, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String folder(String config) {
        try {
            return String.valueOf(json.readValue(config, new TypeReference<Map<String, Object>>() {}).getOrDefault("folder_path", ""));
        } catch (Exception e) {
            return "";
        }
    }

    /** Live entries, or (deleted = true) deleted ones, kept only for their history. */
    List<Entry> entries(boolean deleted) {
        return db.sql("""
                        SELECT s.id, s.source, s.name, s.config, s.default_group_ids, s.enabled, s.last_synced_at, s.last_status,
                               s.last_error, s.deleted_at, s.sync_interval_minutes,
                               (SELECT count(DISTINCT k.parent_id) FROM knowledge_records k WHERE k.sync_source_id = s.id) AS records,
                               (SELECT count(*) FROM ingest_skips i WHERE i.sync_source_id = s.id) AS skipped,
                               EXISTS (SELECT 1 FROM sync_runs r WHERE r.sync_source_id = s.id AND r.status = 'running'
                                       AND r.stop_requested_by IS NOT NULL) AS stopping
                        FROM sync_sources s WHERE s.deleted_at IS""" + (deleted ? " NOT NULL" : " NULL") + " ORDER BY lower(s.name)")
                .query((rs, i) -> new Entry(rs.getString("id"), rs.getString("source"), rs.getString("name"), folder(rs.getString("config")),
                        tags(rs.getString("default_group_ids")), rs.getInt("enabled") == 1, ago(rs.getString("last_synced_at")),
                        rs.getString("last_status"), rs.getString("last_error"), rs.getInt("records"), rs.getInt("skipped"),
                        rs.getInt("stopping") == 1, queue.isPending(rs.getString("id")),
                        rs.getString("deleted_at") == null ? null : ago(rs.getString("deleted_at")),
                        (Integer) rs.getObject("sync_interval_minutes"),
                        SyncScheduler.describe((Integer) rs.getObject("sync_interval_minutes"), rs.getString("last_synced_at"),
                                rs.getInt("enabled") == 1, ZoneId.systemDefault()),
                        null, 0))
                .list().stream().map(this::withFile).toList();
    }

    /** An upload entry with its stored file's name and size (or none, if it is gone from UPLOAD_DIR). */
    private Entry withFile(Entry e) {
        if (!e.upload() || e.deleted() != null) return e;
        try {
            Path f = uploads.storedFile(e.id());
            if (f == null) return e;
            return new Entry(e.id(), e.source(), e.name(), e.folder(), e.tags(), e.enabled(), e.synced(), e.status(), e.error(),
                    e.records(), e.skipped(), e.stopping(), e.queued(), e.deleted(), e.interval(), e.schedule(),
                    f.getFileName().toString(), Math.max(1, Files.size(f) / 1024));
        } catch (java.io.IOException ex) {
            return e;
        }
    }

    private Entry entry(String id) {
        return entries(false).stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
    }

    /** For the history pages: a deleted entry's runs stay viewable. */
    private Entry entryOrDeleted(String id) {
        Entry e = entry(id);
        return e != null ? e : entries(true).stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
    }

    private List<String> groups() {
        return db.sql("SELECT name FROM groups ORDER BY name").query(String.class).list();
    }

    private static String norm(String p) {
        return Path.of(p).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT); // Windows paths ignore case
    }

    private List<String> validateTags(List<String> tags) {
        List<String> errors = new ArrayList<>();
        if (tags.isEmpty()) errors.add("Pick at least one tag: an entry without one would sync documents nobody can see.");
        List<String> known = groups();
        List<String> unknown = tags.stream().filter(t -> !known.contains(t)).toList();
        if (!unknown.isEmpty()) errors.add("Unknown group(s): " + String.join(", ", unknown) + ".");
        return errors;
    }

    List<String> validate(String name, String folder, List<String> tags, String exceptId) {
        List<String> errors = new ArrayList<>();
        if (name.isEmpty()) errors.add("Give the entry a name.");
        if (folder.isEmpty()) errors.add("Folder path is required.");
        errors.addAll(validateTags(tags));
        if (!folder.isEmpty()) {
            if (!Files.isDirectory(Path.of(folder))) errors.add("That folder doesn't exist or the server can't read it.");
            else {
                // Overlapping folders would put one file in two entries: its tag would flip between them every sync.
                Path me = Path.of(norm(folder));
                for (Entry other : entries(false)) {
                    if (!other.source().equals("filesystem") || other.id().equals(exceptId) || other.folder().isEmpty()) continue;
                    Path them = Path.of(norm(other.folder()));
                    if (me.startsWith(them) || them.startsWith(me))
                        errors.add("This folder overlaps the entry \"" + other.name() + "\" (" + other.folder()
                                + "). One file can only belong to one entry, so pick a folder outside it.");
                }
            }
        }
        return errors;
    }

    /** Back to the Sync list, on this source's tab, with a message. */
    private static String listUrl(String msg, String source) {
        return "redirect:/admin/sync?src=" + (SOURCES.contains(source) ? source : "upload")
                + (msg == null ? "" : "&msg=" + URLEncoder.encode(msg, StandardCharsets.UTF_8));
    }

    // ---------- list ----------

    @GetMapping("")
    String list(@RequestParam(defaultValue = "upload") String src, @RequestParam(defaultValue = "1") int page,
                @RequestParam(defaultValue = "") String msg, HttpServletRequest req, Model model) {
        String source = SOURCES.contains(src) ? src : "upload";
        List<Entry> all = entries(false);
        List<Entry> mine = all.stream().filter(e -> e.source().equals(source)).toList();
        Page p = Page.of(mine.size(), page, req);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : SOURCES) counts.put(s, all.stream().filter(e -> e.source().equals(s)).count());
        model.addAllAttributes(Map.of("tab", "sync", "src", source, "counts", counts, "entries", mine.subList(p.offset(), p.last()),
                "p", p, "msg", msg, "deleted", entries(true).stream().filter(e -> e.source().equals(source)).toList(),
                "anyBusy", all.stream().anyMatch(Entry::busy), "allGroups", groups(),
                "accept", String.join(",", Chunker.SUPPORTED)));
        return "admin/sync";
    }

    // ---------- Upload: one entry per file (CLAUDE.md "Upload", SKB Phase 5b) ----------

    /**
     * The "Add upload" dialog: each file becomes its own Upload entry named after it, tagged as
     * chosen, stored and synced at once. A name matching an existing Upload entry (ignoring capitals)
     * is a new version of that entry and keeps ITS tags; the dialog's tags are ignored and the
     * message says so.
     */
    @PostMapping("/upload")
    String addUploads(@RequestParam(name = "files", required = false) List<MultipartFile> files,
                      @RequestParam(required = false) List<String> tags, @AuthenticationPrincipal User admin) throws Exception {
        List<MultipartFile> chosen = files == null ? List.of() : files.stream().filter(f -> f.getOriginalFilename() != null && !f.getOriginalFilename().isBlank()).toList();
        List<String> t = tagList(tags);
        List<String> unknown = t.stream().filter(g -> !groups().contains(g)).toList();
        String problem = chosen.isEmpty() ? "choose at least one file" : t.isEmpty() ? "pick at least one tag"
                : !unknown.isEmpty() ? "unknown group(s): " + String.join(", ", unknown) : null;
        if (problem != null) return listUrl("Nothing uploaded: " + problem + ".", "upload");
        List<String> added = new ArrayList<>(), replaced = new ArrayList<>(), refused = new ArrayList<>(), toSync = new ArrayList<>();
        for (MultipartFile f : chosen) {
            String name = UploadStore.safeName(f.getOriginalFilename());
            Entry existing = name == null ? null : entries(false).stream()
                    .filter(e -> e.upload() && e.name().equalsIgnoreCase(name)).findFirst().orElse(null);
            try {
                if (existing != null) {
                    if (existing.busy()) { refused.add(name + " (its entry is syncing; try again after)"); continue; }
                    String stored = store(existing.id(), f);
                    replaced.add(stored + " (kept its tags: " + String.join(", ", existing.tags()) + ")");
                    toSync.add(existing.id());
                    continue;
                }
                String reason = UploadStore.refusal(name);
                if (reason != null) { refused.add(f.getOriginalFilename() + " (" + reason + ")"); continue; } // before any entry exists
                String id = UUID.randomUUID().toString();
                db.sql("INSERT INTO sync_sources (id, source, name, config, default_group_ids) VALUES (?, 'upload', ?, ?, ?)")
                        .params(id, name, json.writeValueAsString(Map.of("folder", id)), json.writeValueAsString(t)).update();
                try {
                    added.add(store(id, f));
                    toSync.add(id);
                } catch (IllegalArgumentException tooBig) { // undo the entry it would have had
                    db.sql("DELETE FROM sync_sources WHERE id = ?").param(id).update();
                    uploads.deleteFolder(id);
                    throw tooBig;
                }
            } catch (IllegalArgumentException refusal) {
                refused.add(refusal.getMessage());
            }
        }
        long started = toSync.stream().filter(id -> queue.submit(id, admin.id(), false)).count();
        log.info("upload add by user={} added={} replaced={} refused={}", admin.id(), added, replaced, refused);
        List<String> parts = new ArrayList<>();
        if (!added.isEmpty()) parts.add("Uploaded " + added.size() + ": " + String.join(", ", added) + ".");
        if (!replaced.isEmpty()) parts.add("New version of " + replaced.size() + ": " + String.join("; ", replaced)
                + ". The tags picked in the dialog don't apply to a new version.");
        if (!refused.isEmpty()) parts.add("Refused " + refused.size() + ": " + String.join("; ", refused) + ".");
        if (started > 0) parts.add("Syncing " + started + " file(s) now.");
        return listUrl(parts.isEmpty() ? "Nothing uploaded." : String.join(" ", parts), "upload");
    }

    private String store(String entryId, MultipartFile f) throws java.io.IOException {
        try (var in = f.getInputStream()) {
            return uploads.store(entryId, f.getOriginalFilename(), f.getSize(), in);
        }
    }

    // ---------- add / edit ----------

    private String form(Model model, Entry entry, String name, String folder, List<String> tags, boolean enabled,
                        Integer interval, List<String> errors, HttpServletResponse res) {
        if (!errors.isEmpty()) res.setStatus(400);
        model.addAttribute("tab", "sync");
        model.addAttribute("entry", entry);
        Map<String, Object> f = new LinkedHashMap<>(Map.of("name", name, "folder", folder, "tags", tags, "enabled", enabled));
        f.put("schedule", interval == null ? "off" : String.valueOf(interval));
        model.addAttribute("form", f);
        model.addAttribute("choices", SyncScheduler.CHOICES);
        model.addAttribute("allGroups", groups());
        model.addAttribute("errors", errors);
        return "admin/sync_form";
    }

    /** The form's Schedule: "off" -> null (manual only); a listed choice -> minutes; anything else -> -1 (refused). */
    static Integer interval(String schedule) {
        if (schedule.equals("off")) return null;
        try {
            int m = Integer.parseInt(schedule);
            return SyncScheduler.CHOICES.contains(m) ? m : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static List<String> tagList(List<String> tags) {
        return tags == null ? List.of() : List.copyOf(new TreeSet<>(tags));
    }

    @GetMapping("/new")
    String newPage(Model model, HttpServletResponse res) {
        return form(model, null, "", "", List.of(), true, SyncScheduler.DEFAULT_MINUTES, List.of(), res);
    }

    @PostMapping("/new")
    String create(@RequestParam(defaultValue = "") String name, @RequestParam(name = "folder_path", defaultValue = "") String folder,
                  @RequestParam(required = false) List<String> tags, @RequestParam(required = false) String enabled,
                  @RequestParam(defaultValue = "1440") String schedule,
                  @AuthenticationPrincipal User admin, Model model, HttpServletResponse res) throws Exception {
        name = name.strip();
        folder = folder.strip();
        List<String> t = tagList(tags);
        Integer interval = interval(schedule);
        List<String> errors = validate(name, folder, t, null);
        if (interval != null && interval < 0) errors.add("Pick a schedule from the list.");
        if (!errors.isEmpty()) return form(model, null, name, folder, t, enabled != null, interval, errors, res);
        String id = UUID.randomUUID().toString();
        db.sql("INSERT INTO sync_sources (id, source, name, config, default_group_ids, enabled, sync_interval_minutes) "
                        + "VALUES (?, 'filesystem', ?, ?, ?, ?, ?)")
                .params(id, name, json.writeValueAsString(Map.of("folder_path", folder)), json.writeValueAsString(t), enabled != null, interval)
                .update();
        log.info("sync entry added entry={} name={} folder={} tags={} schedule={} by user={}", id, name, folder, t, interval, admin.id());
        return listUrl("Added " + name + ". Run Sync to pull its documents.", "filesystem");
    }

    @GetMapping("/{id}")
    String editPage(@PathVariable String id, Model model, HttpServletResponse res) {
        Entry e = entry(id);
        if (e == null) return "redirect:/admin/sync";
        return form(model, e, e.name(), e.folder(), e.tags(), e.enabled(), e.interval(), List.of(), res);
    }

    @PostMapping("/{id}")
    @Transactional
    public String update(@PathVariable String id, @RequestParam(defaultValue = "") String name,
                         @RequestParam(name = "folder_path", defaultValue = "") String folder,
                         @RequestParam(required = false) List<String> tags, @RequestParam(required = false) String enabled,
                         @RequestParam(defaultValue = "off") String schedule,
                         @AuthenticationPrincipal User admin, Model model, HttpServletResponse res) throws Exception {
        Entry e = entry(id);
        if (e == null) return "redirect:/admin/sync";
        if (e.upload()) { // an upload's name is its file's name and its folder is the app's own: only tags and Enabled change
            name = e.name();
            folder = "";
            schedule = "off";
        }
        name = name.strip();
        folder = folder.strip();
        List<String> t = tagList(tags);
        Integer interval = interval(schedule);
        List<String> errors = e.upload() ? validateTags(t) : validate(name, folder, t, id);
        if (interval != null && interval < 0) errors.add("Pick a schedule from the list.");
        if (e.busy()) errors.add("A sync of this entry is queued or running; edit it after it finishes.");
        if (!errors.isEmpty()) return form(model, e, name, folder, t, enabled != null, interval, errors, res);
        String tagJson = json.writeValueAsString(t);
        String config = e.upload() ? json.writeValueAsString(Map.of("folder", id)) : json.writeValueAsString(Map.of("folder_path", folder));
        db.sql("UPDATE sync_sources SET name = ?, config = ?, default_group_ids = ?, enabled = ?, sync_interval_minutes = ? WHERE id = ?")
                .params(name, config, tagJson, enabled != null, interval, id).update();
        String msg = "Saved " + name + ".";
        if (!t.equals(tagList(e.tags()))) {
            // Applied to already-synced documents now, not at the next sync: if a sensitive folder was
            // tagged too broadly, the fix must not wait for someone to remember to sync.
            int n = db.sql("UPDATE knowledge_records SET allowed_group_ids = ? WHERE sync_source_id = ?").params(tagJson, id).update();
            msg += " Tags applied to its " + n + " existing section(s).";
        }
        log.info("sync entry updated entry={} name={} folder={} tags={} enabled={} schedule={} by user={}", id, name, folder, t,
                enabled != null, interval, admin.id());
        return listUrl(msg, e.source());
    }

    // ---------- sync now / stop ----------

    /** Confirmation as a page, for when the Sync dialog can't open (JavaScript off). */
    @GetMapping("/{id}/run")
    String confirmPage(@PathVariable String id, Model model) {
        Entry e = entry(id);
        if (e == null) return "redirect:/admin/sync";
        model.addAttribute("tab", "sync");
        model.addAttribute("entry", e);
        return "admin/sync_confirm";
    }

    @PostMapping("/{id}/run")
    String run(@PathVariable String id, @RequestParam(required = false) String reread,
               @RequestParam(name = "files", required = false) MultipartFile file, @AuthenticationPrincipal User admin) throws Exception {
        Entry e = entry(id);
        if (e == null) return "redirect:/admin/sync";
        if (!e.enabled() || e.busy()) return listUrl("Not started: that entry is disabled, or already queued or syncing.", e.source());
        String stored = "";
        if (e.upload()) {
            // An upload's Sync = a new version of its file, then sync (CLAUDE.md). No Re-read: the app only
            // holds what was uploaded, and uploading again re-reads it.
            reread = null;
            if (file == null || file.getOriginalFilename() == null || file.getOriginalFilename().isBlank())
                return listUrl("Not started: choose the new version of the file.", "upload");
            try {
                stored = "New version of " + e.name() + " stored (" + store(id, file) + "). ";
            } catch (IllegalArgumentException refusal) {
                return listUrl("Refused: " + refusal.getMessage() + ". Nothing stored, no sync started.", "upload");
            }
        }
        if (!queue.submit(id, admin.id(), reread != null))
            return listUrl(stored + "Not started: that entry is already queued or syncing.", e.source());
        log.info("sync queued entry={} reread={} by user={}", id, reread != null, admin.id());
        return listUrl(stored + (reread != null ? "Re-read of all files in " : "Sync of ") + e.name() + " started.", e.source());
    }

    /** Ask a running sync to stop (any admin). It finishes the file it's on, then ends as stopped. */
    @PostMapping("/{id}/stop")
    String stop(@PathVariable String id, @RequestParam(defaultValue = "") String back, @AuthenticationPrincipal User admin) {
        int asked = db.sql("UPDATE sync_runs SET stop_requested_by = ?, stop_requested_at = ? "
                        + "WHERE sync_source_id = ? AND status = 'running' AND stop_requested_by IS NULL")
                .params(admin.id(), SyncService.time(Instant.now()), id).update();
        log.info("sync stop requested entry={} by user={} ({})", id, admin.id(), asked > 0 ? "ok" : "nothing running");
        String msg = asked > 0 ? "Stopping: the sync finishes the file it's on, then stops. Everything done so far is kept."
                : "Nothing to stop: that sync isn't running (or a stop was already requested).";
        if (back.equals("history"))
            return "redirect:/admin/sync/" + id + "/history?msg=" + URLEncoder.encode(msg, StandardCharsets.UTF_8);
        Entry e = entry(id);
        return listUrl(msg, e == null ? null : e.source());
    }

    // ---------- delete ----------

    /**
     * Filesystem: removes the entry's records (search and chat) and skip log; the row stays, disabled
     * and marked deleted, so its history is kept and viewable (SKB). The folder is never touched.
     * Upload: removes the file, its records, its history and the entry (CLAUDE.md "Upload").
     * Confirmed in a dialog first.
     */
    @PostMapping("/{id}/delete")
    @Transactional
    public String delete(@PathVariable String id, @AuthenticationPrincipal User admin) {
        Entry e = entry(id);
        if (e == null) return "redirect:/admin/sync";
        // Atomic with the sync claim: once marked deleted no sync starts, and a running one makes this a no-op.
        if (queue.isPending(id) || db.sql("UPDATE sync_sources SET enabled = 0, deleted_at = ? "
                        + "WHERE id = ? AND deleted_at IS NULL AND last_status IS NOT 'syncing'")
                .params(SyncService.time(Instant.now()), id).update() == 0)
            return listUrl("Not deleted: it is queued or syncing. Delete after it finishes.", e.source());
        int n = db.sql("DELETE FROM knowledge_records WHERE sync_source_id = ?").param(id).update();
        db.sql("DELETE FROM ingest_skips WHERE sync_source_id = ?").param(id).update();
        if (e.upload()) {
            db.sql("DELETE FROM sync_run_items WHERE sync_run_id IN (SELECT id FROM sync_runs WHERE sync_source_id = ?)").param(id).update();
            db.sql("DELETE FROM sync_runs WHERE sync_source_id = ?").param(id).update();
            db.sql("DELETE FROM sync_sources WHERE id = ?").param(id).update();
            try {
                uploads.deleteFolder(id);
            } catch (java.io.IOException ex) { // the records are gone either way; say what is left on disk
                log.warn("upload entry {} deleted but its folder could not be removed: {}", id, ex.toString());
            }
            log.info("upload entry deleted entry={} name={} records={} by user={}", id, e.name(), n, admin.id());
            return listUrl("Deleted " + e.name() + ": the file, its " + n + " section(s) and its sync history are gone.", "upload");
        }
        log.info("sync entry deleted entry={} name={} records={} by user={}", id, e.name(), n, admin.id());
        return listUrl("Deleted " + e.name() + ": its " + n + " section(s) left search and chat. Its sync history is kept (Deleted entries, below).", "filesystem");
    }

    // ---------- history ----------

    private static final String RUN_COLS = "r.id, r.started_at, r.finished_at, r.status, r.error_message, r.records_inserted, "
            + "r.records_updated, r.records_deleted, r.records_skipped, u.name, u.username, r.reread";

    private static Run run(java.sql.ResultSet rs) throws java.sql.SQLException {
        String started = rs.getString(2), finished = rs.getString(3);
        long s = Duration.between(Instant.parse(started), finished == null ? Instant.now() : Instant.parse(finished)).getSeconds();
        return new Run(rs.getString(1), shown(started), finished == null ? null : shown(finished), rs.getString(4), rs.getString(5),
                s >= 60 ? s / 60 + "m " + String.format("%02d", s % 60) + "s" : s + "s",
                rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getString(10), rs.getString(11), rs.getInt(12) == 1);
    }

    @GetMapping("/{id}/history")
    String history(@PathVariable String id, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "") String msg,
                   HttpServletRequest req, Model model) {
        Entry e = entryOrDeleted(id);
        if (e == null) return "redirect:/admin/sync";
        int total = db.sql("SELECT count(*) FROM sync_runs WHERE sync_source_id = ?").param(id).query(Integer.class).single();
        Page p = Page.of(total, page, req);
        List<Run> runs = db.sql("SELECT " + RUN_COLS + " FROM sync_runs r LEFT JOIN users u ON u.id = r.triggered_by "
                        + "WHERE r.sync_source_id = ? ORDER BY r.started_at DESC, r.id LIMIT ? OFFSET ?")
                .params(id, p.size(), p.offset()).query((rs, i) -> run(rs)).list();
        model.addAllAttributes(Map.of("tab", "sync", "entry", e, "runs", runs, "p", p, "msg", msg,
                "anyRunning", e.busy() || runs.stream().anyMatch(r -> r.status().equals("running"))));
        return "admin/sync_history";
    }

    @GetMapping("/{id}/runs/{runId}")
    String runPage(@PathVariable String id, @PathVariable String runId, @RequestParam(defaultValue = "1") int page,
                   @RequestParam(defaultValue = "") String action, HttpServletRequest req, Model model) {
        Entry e = entryOrDeleted(id);
        Run run = e == null ? null : db.sql("SELECT " + RUN_COLS + " FROM sync_runs r LEFT JOIN users u ON u.id = r.triggered_by "
                + "WHERE r.id = ? AND r.sync_source_id = ?").params(runId, id).query((rs, i) -> run(rs)).optional().orElse(null);
        if (run == null) return e == null ? "redirect:/admin/sync" : "redirect:/admin/sync/" + id + "/history"; // must be this entry's run
        String a = ACTIONS.contains(action) ? action : "";
        Map<String, Integer> perAction = new LinkedHashMap<>();
        for (String x : ACTIONS) perAction.put(x, 0);
        db.sql("SELECT action, count(*) FROM sync_run_items WHERE sync_run_id = ? GROUP BY action").param(runId)
                .query((rs, i) -> perAction.put(rs.getString(1), rs.getInt(2))).list();
        int all = perAction.values().stream().mapToInt(Integer::intValue).sum();
        Page p = Page.of(a.isEmpty() ? all : perAction.get(a), page, req);
        String root = e.folder().replace('\\', '/').replaceAll("/+$", "") + "/";
        List<Item> items = db.sql("SELECT parent_id, action, chunk_count FROM sync_run_items WHERE sync_run_id = ? AND (? = '' OR action = ?) "
                        + "ORDER BY parent_id, id LIMIT ? OFFSET ?")
                .params(runId, a, a, p.size(), p.offset())
                .query((rs, i) -> {
                    String path = rs.getString(1);
                    String shortPath = root.length() > 1 && path.toLowerCase(Locale.ROOT).startsWith(root.toLowerCase(Locale.ROOT))
                            ? path.substring(root.length()) : path;
                    return new Item(path, shortPath, rs.getString(2), rs.getInt(3));
                }).list();
        model.addAllAttributes(Map.of("tab", "sync", "entry", e, "run", run, "items", items, "p", p, "action", a,
                "actions", ACTIONS, "perAction", perAction, "allCount", all));
        return "admin/sync_run";
    }
}
