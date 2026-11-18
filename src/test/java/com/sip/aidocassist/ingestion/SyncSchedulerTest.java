package com.sip.aidocassist.ingestion;

import com.sip.aidocassist.AiDocAssistApplicationAccess;
import com.sip.aidocassist.db.Migrator;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** Which entries the ticker queues (CLAUDE.md "Scheduler"), against a real schema; the queue only records. */
class SyncSchedulerTest {

    /** Records submits and, like the real queue, refuses an entry that is already queued or running. */
    static class RecordingQueue extends SyncQueue {
        final List<String> submitted = new ArrayList<>();
        final Set<String> pending = ConcurrentHashMap.newKeySet();
        RecordingQueue() { super(null); }
        @Override public boolean submit(String id, String by, boolean reread) {
            assertNull(by, "a scheduled run has no admin");
            if (!pending.add(id)) return false;
            submitted.add(id);
            return true;
        }
    }

    @TempDir Path tmp;
    HikariDataSource ds;
    JdbcClient db;
    final Instant now = Instant.parse("2026-10-13T12:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        ds = (HikariDataSource) AiDocAssistApplicationAccess.dataSource(tmp.resolve("s.db").toString());
        Migrator.migrate(ds);
        db = JdbcClient.create(ds);
    }

    @AfterEach
    void tearDown() { ds.close(); }

    private void entry(String id, Integer minutes, Duration syncedAgo, String status, boolean enabled, boolean deleted) {
        db.sql("INSERT INTO sync_sources (id, source, name, config, default_group_ids, enabled, sync_interval_minutes, last_synced_at, last_status, deleted_at) "
                        + "VALUES (?, 'filesystem', ?, '{}', '[\"general\"]', ?, ?, ?, ?, ?)")
                .params(id, id, enabled, minutes, syncedAgo == null ? null : SyncService.time(now.minus(syncedAgo)), status,
                        deleted ? SyncService.time(now) : null).update();
    }

    @Test
    void queuesExactlyTheEntriesThatAreDue() {
        entry("never-synced", 1440, null, null, true, false);
        entry("hourly-61min", 60, Duration.ofMinutes(61), "ok", true, false);
        entry("hourly-exactly-due", 60, Duration.ofMinutes(60), "failed", true, false); // a failed run counts as a sync
        entry("hourly-30min", 60, Duration.ofMinutes(30), "ok", true, false);
        entry("daily-3-days-overdue", 1440, Duration.ofDays(3), "stopped", true, false);
        entry("off", null, null, null, true, false);
        entry("disabled", 60, Duration.ofDays(1), "ok", false, false);
        entry("deleted", 60, Duration.ofDays(1), "ok", true, true);
        entry("syncing-now", 60, Duration.ofDays(1), "syncing", true, false);

        RecordingQueue queue = new RecordingQueue();
        SyncScheduler scheduler = new SyncScheduler(db, queue);
        assertEquals(Set.of("never-synced", "hourly-61min", "hourly-exactly-due", "daily-3-days-overdue"), Set.copyOf(scheduler.tick(now)));

        // Next minute, nothing has finished yet: nothing is queued twice; an overdue entry ran once, not once per missed interval.
        assertEquals(List.of(), scheduler.tick(now.plusSeconds(60)));
        assertEquals(4, queue.submitted.size());
    }

    @Test
    void describesTheScheduleForTheList() {
        var zone = ZoneOffset.UTC;
        assertEquals("Manual only", SyncScheduler.describe(null, null, true, zone));
        assertEquals("Every 6 h (disabled)", SyncScheduler.describe(360, null, false, zone));
        assertEquals("Every 24 h · next: soon", SyncScheduler.describe(1440, null, true, zone));
        assertEquals("Every 1 h · next: soon", SyncScheduler.describe(60, SyncService.time(Instant.now().minus(Duration.ofHours(2))), true, zone));
        Instant last = Instant.now().minus(Duration.ofMinutes(10));
        String next = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(last.plus(Duration.ofHours(1)));
        String shown = SyncScheduler.describe(60, SyncService.time(last), true, zone);
        assertTrue(shown.startsWith("Every 1 h · next ~") && shown.endsWith(next), shown); // a day name goes in between after midnight
    }
}
