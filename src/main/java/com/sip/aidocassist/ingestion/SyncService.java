package com.sip.aidocassist.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sip.aidocassist.chunking.Chunker;
import com.sip.aidocassist.chunking.Pack.Chunk;
import com.sip.aidocassist.llm.Embedder;
import com.sip.aidocassist.ollama.OllamaClient;
import com.sip.aidocassist.sources.FilesystemAdapter;
import com.sip.aidocassist.sources.SourceAdapter;
import com.sip.aidocassist.sources.SourceObject;
import com.sip.aidocassist.sources.UploadAdapter;
import com.sip.aidocassist.sources.UploadStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Brings one sync entry's records up to date with its source (CLAUDE.md "Re-sync", port of SKB
 * ingestion/pipeline.py). Every run writes sync_runs + one sync_run_items row per item, and ends
 * as success / failed / stopped, never left at running.
 *
 * Network calls (embedding) happen outside database transactions; each item's writes are one short
 * transaction, committed on its own, so a crash later keeps what already landed.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    static final int EMBED_BATCH = 8;
    /** Fixed width, so SQL max()/comparison on the text is time order. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    public static final String INTERRUPTED = "Interrupted: the app stopped during the sync. Run it again.";

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Embedder ollama;
    private final ObjectMapper json;
    private final Map<String, SourceAdapter> adapters;

    public SyncService(JdbcClient db, TransactionTemplate tx, Embedder ollama, ObjectMapper json, UploadStore uploads) {
        this.db = db;
        this.tx = tx;
        this.ollama = ollama;
        this.json = json;
        this.adapters = Map.of("filesystem", new FilesystemAdapter(), "upload", new UploadAdapter(uploads));
    }

    public static String time(Instant t) {
        return TIME.format(t);
    }

    private static final class Counts {
        int inserted, updated, deleted, skipped;
        void add(Counts o) { inserted += o.inserted; updated += o.updated; deleted += o.deleted; skipped += o.skipped; }
    }

    /** For the history: one action for the item, its chunk count, and record-level counts. */
    private record Result(String action, int chunkCount, Counts counts) {}

    private record Entry(String source, String name, Map<String, Object> config, String tags, String deletedAt) {}

    /**
     * Run one sync entry. triggeredBy = the admin's user id ("Synchronize now"), null for a scheduled
     * or command-line run. reread = ignore dates and re-parse every file ("Re-read all"); checksums
     * still limit re-embedding to sections whose text changed.
     *
     * @return the sync_runs id, or null if the entry is deleted or already syncing
     */
    public String run(String entryId, String triggeredBy, boolean reread) {
        Entry e = db.sql("SELECT source, name, config, default_group_ids, deleted_at FROM sync_sources WHERE id = ?")
                .param(entryId)
                .query((rs, i) -> new Entry(rs.getString(1), rs.getString(2), parse(rs.getString(3)), rs.getString(4), rs.getString(5)))
                .optional().orElseThrow(() -> new IllegalArgumentException("no sync entry " + entryId));
        if (e.deletedAt() != null) {
            log.info("sync {}: skipped, the entry was deleted", e.name());
            return null;
        }
        // Claim it: a second run of the same entry (another click, the scheduler) is refused.
        if (db.sql("UPDATE sync_sources SET last_status = 'syncing' WHERE id = ? AND last_status IS NOT 'syncing'")
                .param(entryId).update() == 0) {
            log.info("sync {}: skipped, it is already syncing", e.name());
            return null;
        }
        String runId = UUID.randomUUID().toString();
        db.sql("INSERT INTO sync_runs (id, sync_source_id, triggered_by, started_at, reread) VALUES (?, ?, ?, ?, ?)")
                .params(runId, entryId, triggeredBy, time(Instant.now()), reread).update(); // visible as running at once
        log.info("sync {} ({}) tags={}{}", e.name(), e.source(), e.tags(), reread ? " re-read all" : "");
        Counts counts = new Counts();
        List<String> modelErrors = new ArrayList<>();
        try {
            List<SourceObject> objs = adapters.get(e.source()).fetch(e.config());
            Set<String> listed = new HashSet<>();
            for (SourceObject o : objs) listed.add(o.parentId());
            for (SourceObject obj : objs) {
                // An admin's Stop: checked between items, never mid-item. Items done so far stay; the next
                // sync carries on from there (they're skipped as unchanged).
                String stop = db.sql("SELECT u.name || ' (requested ' || replace(substr(r.stop_requested_at, 1, 16), 'T', ' ') || ')' "
                        + "FROM sync_runs r JOIN users u ON u.id = r.stop_requested_by WHERE r.id = ?").param(runId).query(String.class).optional().orElse(null);
                if (stop != null) {
                    String message = "Stopped by " + stop;
                    // Counts as a sync for the schedule: the ticker must not undo an admin's Stop a minute later.
                    db.sql("UPDATE sync_sources SET last_status = 'stopped', last_error = ?, last_synced_at = ? WHERE id = ?")
                            .params(message, time(Instant.now()), entryId).update();
                    finish(runId, "stopped", message, counts);
                    log.info("sync {}: {}", e.name(), message);
                    return runId;
                }
                Result r;
                try {
                    r = process(entryId, runId, e, obj, reread);
                    db.sql("DELETE FROM ingest_skips WHERE sync_source_id = ? AND item = ? AND reason = 'model-error'")
                            .params(entryId, obj.parentId()).update(); // it went through this time
                } catch (HttpServerErrorException ex) {
                    // Ollama failed on this one item (seen in SKB: "CUDA error" on the server). Nothing stored, so
                    // the next sync retries it. An unreachable Ollama is a different exception and stops the run.
                    modelErrors.add(obj.parentId());
                    skip(entryId, obj.parentId(), "model-error", ex.getStatusCode().value() + ": " + cut(ex.getResponseBodyAsString(), 300));
                    r = null;
                }
                if (r != null) counts.add(r.counts());
            }
            inventory(entryId, runId, e, listed, counts);
            db.sql("UPDATE sync_sources SET last_status = 'ok', last_synced_at = ?, last_error = NULL WHERE id = ?")
                    .params(time(Instant.now()), entryId).update();
            finish(runId, "success", modelErrors.isEmpty() ? null
                    : cut(modelErrors.size() + " skipped after a model server error, retried next sync: " + String.join(", ", modelErrors), 500), counts);
            log.info("sync {}: done, inserted {} updated {} deleted {} unchanged {}", e.name(), counts.inserted, counts.updated, counts.deleted, counts.skipped);
        } catch (Exception ex) {
            String error = cut(ex.getClass().getSimpleName() + ": " + ex.getMessage(), 500);
            // A failed run still counts as a sync for the schedule (CLAUDE.md Scheduler): no retry every minute.
            db.sql("UPDATE sync_sources SET last_status = 'failed', last_error = ?, last_synced_at = ? WHERE id = ?")
                    .params(error, time(Instant.now()), entryId).update();
            finish(runId, "failed", error, counts); // counts cover the items committed before the failure
            log.warn("sync {}: failed: {}", e.name(), error);
        }
        return runId;
    }

    /**
     * Both sources list everything they hold (a folder; an upload entry's own folder), so records of
     * files no longer listed are deleted: a file removed from the folder, or an upload replaced by a
     * version under another name.
     */
    private void inventory(String entryId, String runId, Entry e, Set<String> listed, Counts counts) {
        List<String> held = db.sql("SELECT DISTINCT parent_id FROM knowledge_records WHERE sync_source_id = ? AND source = ?")
                .params(entryId, e.source()).query(String.class).list();
        List<String> missing = held.stream().filter(p -> !listed.contains(p)).sorted().toList();
        // Uploads are exempt: the app owns that folder, and a missing folder already fails in the adapter.
        if (!missing.isEmpty() && listed.isEmpty() && e.source().equals("filesystem"))
            // An empty listing is far likelier a missing drive or mount than everything deleted at once.
            throw new IllegalStateException("The folder listed nothing, but this entry holds " + held.size()
                    + " file(s); not deleting them. Check the folder. If it really is empty now, disable or delete the entry.");
        for (String parent : missing) {
            tx.executeWithoutResult(s -> {
                int n = db.sql("DELETE FROM knowledge_records WHERE sync_source_id = ? AND source = ? AND parent_id = ?")
                        .params(entryId, e.source(), parent).update();
                item(runId, parent, "deleted", n);
                counts.deleted += n;
            });
            log.info("  {}: gone from the folder, records deleted", parent);
        }
    }

    /** The re-sync steps for one item. Null when it was filtered out (that goes to ingest_skips, not the history). */
    private Result process(String entryId, String runId, Entry e, SourceObject obj, boolean reread) throws Exception {
        if (obj.skip() != null) {
            skip(entryId, obj.parentId(), obj.skip().reason(), obj.skip().detail());
            return null;
        }
        // Step 1: unchanged at the source -> no parse, no embedding. Tags, entry and item metadata are
        // still refreshed, so an admin's tag change applies on the next sync.
        var stored = db.sql("SELECT max(updated_at), count(*) FROM knowledge_records WHERE source = ? AND parent_id = ?")
                .params(e.source(), obj.parentId()).query((rs, i) -> new Object[]{rs.getString(1), rs.getInt(2)}).single();
        String newest = (String) stored[0];
        int existingCount = (int) stored[1];
        if (newest != null && !obj.modifiedAt().isAfter(Instant.parse(newest)) && !reread) {
            String meta = json.writeValueAsString(obj.metadata());
            tx.executeWithoutResult(s -> {
                db.sql("UPDATE knowledge_records SET allowed_group_ids = ?, sync_source_id = ?, source_url = ?, "
                                + "metadata = json_patch(metadata, ?) WHERE source = ? AND parent_id = ?")
                        .params(e.tags(), entryId, obj.sourceUrl(), meta, e.source(), obj.parentId()).update();
                item(runId, obj.parentId(), "skipped", existingCount);
            });
            log.info("  {}: unchanged", obj.parentId());
            Counts c = new Counts();
            c.skipped = existingCount;
            return new Result("skipped", existingCount, c);
        }

        // Step 2: parse and chunk.
        long started = System.currentTimeMillis();
        Chunker.Result parsed;
        try {
            parsed = Chunker.chunk(obj.file());
        } catch (Exception ex) { // one broken file must not fail the whole folder
            skip(entryId, obj.parentId(), "parse-failed", cut(ex.getClass().getSimpleName() + ": " + ex.getMessage(), 500));
            return null;
        }
        for (String sheet : parsed.hiddenSheets()) skip(entryId, obj.parentId() + " :: " + sheet, "hidden-sheet", null);
        List<Chunk> chunks = parsed.chunks();
        if (chunks.isEmpty()) skip(entryId, obj.parentId(), "empty", "no text after parsing");

        record Rec(String sourceId, int index, Chunk chunk, String checksum) {}
        List<Rec> records = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk ch = chunks.get(i);
            String sid = obj.parentId() + "#" + i;
            String secret = Filters.findSecret(ch.title() + "\n" + ch.text());
            if (secret != null) { // never embedded, stored or shown; the pattern name is logged, not the secret
                skip(entryId, sid, "secret-suspected", secret);
                continue;
            }
            records.add(new Rec(sid, i, ch, sha256(json.writeValueAsString(List.of(ch.title(), ch.text())))));
        }

        // Step 3: embed only new or changed chunks (checksums decide, not dates), and chunks embedded by a
        // model other than the preferred one (an earlier Gemini outage, or the switch from Ollama).
        Map<String, String> existing = new HashMap<>();
        Map<String, String> models = new HashMap<>();
        db.sql("SELECT source_id, content_checksum, coalesce(embed_model, ?) FROM knowledge_records WHERE source = ? AND parent_id = ?")
                .params(ollama.legacy(), e.source(), obj.parentId())
                .query((rs, i) -> { existing.put(rs.getString(1), rs.getString(2)); return models.put(rs.getString(1), rs.getString(3)); }).list();
        String preferred = ollama.preferred();
        List<Rec> changed = records.stream().filter(r -> !r.checksum().equals(existing.get(r.sourceId()))
                || !preferred.equals(models.get(r.sourceId()))).toList();
        Map<String, byte[]> vectors = new HashMap<>();
        Map<String, String> madeBy = new HashMap<>();
        if (!changed.isEmpty())
            log.info("  {}: {} chunks parsed ({}s), {} to embed", obj.parentId(), records.size(),
                    (System.currentTimeMillis() - started) / 1000, changed.size());
        long lastProgress = System.currentTimeMillis();
        for (int b = 0; b < changed.size(); b += EMBED_BATCH) {
            List<Rec> batch = changed.subList(b, Math.min(b + EMBED_BATCH, changed.size()));
            // The title (heading path) is embedded with the text: a bare field table means little without it.
            Embedder.Embedded v = ollama.embed(batch.stream().map(r -> r.chunk().title() + "\n\n" + r.chunk().text()).toList());
            for (int k = 0; k < batch.size(); k++) {
                vectors.put(batch.get(k).sourceId(), OllamaClient.toBytes(v.vectors()[k]));
                madeBy.put(batch.get(k).sourceId(), v.model());
            }
            // A large file embeds for minutes (the Gemini budget is ~25k tokens a minute): say how far it is.
            if (System.currentTimeMillis() - lastProgress >= 30_000 && b + EMBED_BATCH < changed.size()) {
                log.info("  {}: embedded {}/{} chunks", obj.parentId(), b + batch.size(), changed.size());
                lastProgress = System.currentTimeMillis();
            }
        }

        // Steps 3-5 written as one transaction. Unchanged chunks are upserted too (embedding kept) so
        // updated_at moves forward; otherwise a file saved without edits would be re-parsed every sync.
        List<String> keep = records.stream().map(Rec::sourceId).toList();
        int inserted = (int) changed.stream().filter(r -> !existing.containsKey(r.sourceId())).count();
        int[] gone = new int[1];
        String[] action = new String[1];
        String updatedAt = time(obj.modifiedAt());
        tx.executeWithoutResult(s -> {
            for (Rec r : records) {
                Map<String, Object> meta = new LinkedHashMap<>(obj.metadata());
                meta.put("heading_path", r.chunk().path());
                meta.put("section_part", r.chunk().part());
                meta.put("chunk_index", r.index());
                db.sql("""
                        INSERT INTO knowledge_records (id, source, source_id, parent_id, source_url, source_type, title, content,
                            raw_content, updated_at, allowed_group_ids, sync_source_id, metadata, content_checksum, embedding, embed_model)
                        VALUES (?, ?, ?, ?, ?, 'doc-section', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (source, source_id) DO UPDATE SET
                            parent_id = excluded.parent_id, source_url = excluded.source_url, title = excluded.title,
                            content = excluded.content, raw_content = excluded.raw_content, updated_at = excluded.updated_at,
                            allowed_group_ids = excluded.allowed_group_ids, sync_source_id = excluded.sync_source_id,
                            metadata = excluded.metadata, content_checksum = excluded.content_checksum,
                            embedding = coalesce(excluded.embedding, knowledge_records.embedding),
                            embed_model = coalesce(excluded.embed_model, knowledge_records.embed_model)""")
                        .params(UUID.randomUUID().toString(), e.source(), r.sourceId(), obj.parentId(), obj.sourceUrl(),
                                r.chunk().title(), r.chunk().text(), r.chunk().text(), updatedAt, e.tags(), entryId,
                                writeJson(meta), r.checksum(), vectors.get(r.sourceId()), madeBy.get(r.sourceId()))
                        .update();
            }
            // Step 4: chunk ids this file no longer produces.
            String placeholders = keep.isEmpty() ? "''" : String.join(",", keep.stream().map(k -> "?").toList());
            List<Object> args = new ArrayList<>(List.of(e.source(), obj.parentId()));
            args.addAll(keep);
            gone[0] = db.sql("DELETE FROM knowledge_records WHERE source = ? AND parent_id = ? AND source_id NOT IN (" + placeholders + ")")
                    .params(args).update();
            // The history row goes in with the records it describes.
            if (existing.isEmpty()) action[0] = records.isEmpty() ? null : "inserted"; // new but empty: nothing touched
            else if (records.isEmpty()) action[0] = "deleted";                      // every chunk gone (emptied, or all filtered)
            else action[0] = !changed.isEmpty() || gone[0] > 0 ? "updated" : "skipped"; // re-read but identical
            if (action[0] != null) item(runId, obj.parentId(), action[0], action[0].equals("deleted") ? gone[0] : records.size());
        });

        Counts c = new Counts();
        c.inserted = inserted;
        c.updated = changed.size() - inserted;
        c.deleted = gone[0];
        c.skipped = records.size() - changed.size();
        log.info("  {}: {} chunks, {} embedded, {} removed ({}s)", obj.parentId(), records.size(), changed.size(), gone[0],
                (System.currentTimeMillis() - started) / 1000);
        return action[0] == null ? null : new Result(action[0], records.size(), c);
    }

    private void item(String runId, String parentId, String action, int chunkCount) {
        db.sql("INSERT INTO sync_run_items (id, sync_run_id, parent_id, action, chunk_count) VALUES (?, ?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), runId, parentId, action, chunkCount).update();
    }

    private void finish(String runId, String status, String error, Counts c) {
        db.sql("UPDATE sync_runs SET status = ?, error_message = ?, finished_at = ?, records_inserted = ?, records_updated = ?, "
                        + "records_deleted = ?, records_skipped = ? WHERE id = ?")
                .params(status, error, time(Instant.now()), c.inserted, c.updated, c.deleted, c.skipped, runId).update();
    }

    private void skip(String entryId, String item, String reason, String detail) {
        log.info("  skip [{}] {} {}", reason, item, detail == null ? "" : detail);
        db.sql("INSERT INTO ingest_skips (sync_source_id, item, reason, detail, last_seen_at) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (sync_source_id, item, reason) DO UPDATE SET detail = excluded.detail, last_seen_at = excluded.last_seen_at")
                .params(entryId, item, reason, detail, time(Instant.now())).update();
    }

    /** At startup: runs a restart interrupted are marked failed, and their entries no longer "syncing". */
    public int resetInterrupted() {
        String now = time(Instant.now());
        int n = db.sql("UPDATE sync_runs SET status = 'failed', error_message = ?, finished_at = ? WHERE status = 'running'")
                .params(INTERRUPTED, now).update();
        db.sql("UPDATE sync_sources SET last_status = 'failed', last_error = ? WHERE last_status = 'syncing'").param(INTERRUPTED).update();
        return n;
    }

    private Map<String, Object> parse(String config) {
        try {
            return json.readValue(config, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("bad config JSON: " + config, ex);
        }
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
