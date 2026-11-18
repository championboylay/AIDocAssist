package com.sip.aidocassist.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Filesystem entries sync on their own schedule (CLAUDE.md "Scheduler"; not in SKB). Every minute
 * the ticker hands entries that are due to the same queue as "Synchronize now": a scheduled run is
 * a normal run with triggered_by = NULL. Scheduled from {@code SchedulingConfig} in the web app only.
 *
 * Due = enabled, not deleted, has an interval, not syncing, and never synced or
 * last_synced_at + interval <= now. A failed or stopped run sets last_synced_at too, so it counts
 * as a sync: no retry every minute, and an admin's Stop isn't undone a minute later. After downtime
 * an overdue entry runs once (it is due once, and the queue never holds an entry twice).
 *
 * ponytail: fixed interval choices, no cron expressions; add a time-of-day option if the owner
 * needs syncs outside working hours.
 */
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);
    /** The Schedule choices on the entry form, in minutes; null = Off (manual only). */
    public static final List<Integer> CHOICES = List.of(60, 360, 720, 1440);
    public static final int DEFAULT_MINUTES = 1440;

    private final JdbcClient db;
    private final SyncQueue queue;

    public SyncScheduler(JdbcClient db, SyncQueue queue) {
        this.db = db;
        this.queue = queue;
    }

    /** One tick: queue every entry that is due. Returns the ids queued (for tests and the log). */
    public List<String> tick(Instant now) {
        List<String> queued = new ArrayList<>();
        db.sql("""
                        SELECT id, name, sync_interval_minutes, last_synced_at FROM sync_sources
                        WHERE source = 'filesystem' AND enabled = 1 AND deleted_at IS NULL AND sync_interval_minutes IS NOT NULL
                          AND last_status IS NOT 'syncing'""")
                .query((rs, i) -> {
                    String last = rs.getString("last_synced_at");
                    boolean due = last == null || !Instant.parse(last).plus(Duration.ofMinutes(rs.getInt("sync_interval_minutes"))).isAfter(now);
                    if (due && queue.submit(rs.getString("id"), null, false)) {
                        queued.add(rs.getString("id"));
                        log.info("scheduled sync queued: {}", rs.getString("name"));
                    }
                    return null;
                }).list();
        return queued;
    }

    /** "Every 6 h · next ~14:30" for the Sync list; "Manual only" when Off. */
    public static String describe(Integer minutes, String lastSyncedAt, boolean enabled, java.time.ZoneId zone) {
        if (minutes == null) return "Manual only";
        String every = "Every " + (minutes % 60 == 0 ? minutes / 60 + " h" : minutes + " min");
        if (!enabled) return every + " (disabled)";
        if (lastSyncedAt == null) return every + " · next: soon";
        Instant next = Instant.parse(lastSyncedAt).plus(Duration.ofMinutes(minutes));
        if (!next.isAfter(Instant.now())) return every + " · next: soon";
        boolean today = next.atZone(zone).toLocalDate().equals(java.time.LocalDate.now(zone));
        return every + " · next ~" + java.time.format.DateTimeFormatter.ofPattern(today ? "HH:mm" : "EEE HH:mm").withZone(zone).format(next);
    }
}
