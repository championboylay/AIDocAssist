package com.sip.aidocassist.ingestion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

/**
 * Runs the sync ticker once a minute, in the web app only (the seed/sync commands must not start
 * scheduled syncs). sync.scheduler=false turns it off (tests drive SyncScheduler.tick directly).
 */
@Configuration
@EnableScheduling
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "sync.scheduler", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    private final SyncScheduler scheduler;

    public SchedulingConfig(JdbcClient db, SyncQueue queue) {
        this.scheduler = new SyncScheduler(db, queue);
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 60_000)
    void tick() {
        scheduler.tick(Instant.now());
    }
}
