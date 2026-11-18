package com.sip.aidocassist.ingestion;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one sync thread (CLAUDE.md: one writer). "Synchronize now" and, in 2d, the scheduler hand
 * entries here; they run one after another. An entry already queued or running is not queued again.
 */
@Component
public class SyncQueue {

    private static final Logger log = LoggerFactory.getLogger(SyncQueue.class);

    private final SyncService sync;
    private final Set<String> pending = ConcurrentHashMap.newKeySet(); // queued or running
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sync");
        t.setDaemon(true); // an app shutdown doesn't wait; the next start marks the run interrupted
        return t;
    });

    public SyncQueue(SyncService sync) {
        this.sync = sync;
    }

    /** False when this entry is already queued or running. */
    public boolean submit(String entryId, String triggeredBy, boolean reread) {
        if (!pending.add(entryId)) return false;
        worker.submit(() -> {
            try {
                sync.run(entryId, triggeredBy, reread);
            } catch (RuntimeException e) { // run() records its own failures; this is a bug or a missing entry
                log.error("sync {} crashed", entryId, e);
            } finally {
                pending.remove(entryId);
            }
        });
        return true;
    }

    public boolean isPending(String entryId) {
        return pending.contains(entryId);
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
