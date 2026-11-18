-- Phase 0 schema, as in CLAUDE.md. Never edit once applied; add 002_*.sql instead.

CREATE TABLE users (
    id            TEXT PRIMARY KEY,
    email         TEXT UNIQUE NOT NULL COLLATE NOCASE,
    name          TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    active        INTEGER NOT NULL DEFAULT 1,
    is_admin      INTEGER NOT NULL DEFAULT 0,
    deleted_at    TEXT
);

CREATE TABLE groups (
    id   TEXT PRIMARY KEY,
    name TEXT UNIQUE NOT NULL
);

CREATE TABLE user_groups (
    user_id  TEXT NOT NULL REFERENCES users(id),
    group_id TEXT NOT NULL REFERENCES groups(id),
    PRIMARY KEY (user_id, group_id)
);

CREATE TABLE sync_sources (
    id                TEXT PRIMARY KEY,
    source            TEXT NOT NULL,
    name              TEXT NOT NULL,
    config            TEXT NOT NULL,
    default_group_ids TEXT NOT NULL DEFAULT '[]',
    enabled           INTEGER NOT NULL DEFAULT 1,
    last_synced_at    TEXT,
    last_status       TEXT,
    last_error        TEXT,
    sync_interval_minutes INTEGER,
    deleted_at        TEXT
);

CREATE TABLE knowledge_records (
    id                TEXT PRIMARY KEY,
    source            TEXT NOT NULL,
    source_id         TEXT NOT NULL,
    parent_id         TEXT NOT NULL,
    source_url        TEXT,
    source_type       TEXT NOT NULL,
    title             TEXT,
    content           TEXT NOT NULL,
    raw_content       TEXT,
    updated_at        TEXT NOT NULL,
    authority         REAL NOT NULL DEFAULT 0.5,
    allowed_group_ids TEXT NOT NULL DEFAULT '[]',
    sync_source_id    TEXT REFERENCES sync_sources(id),
    metadata          TEXT NOT NULL DEFAULT '{}',
    content_checksum  TEXT NOT NULL,
    embedding         BLOB,
    UNIQUE (source, source_id)
);
CREATE INDEX kr_parent_idx ON knowledge_records(parent_id);

-- External-content FTS keyed on knowledge_records' implicit rowid.
-- ponytail: that rowid can change on VACUUM (no INTEGER PRIMARY KEY); after a VACUUM run
-- INSERT INTO knowledge_fts(knowledge_fts) VALUES('rebuild');
CREATE VIRTUAL TABLE knowledge_fts USING fts5(
    title, content, content='knowledge_records', content_rowid='rowid', tokenize='porter'
);

CREATE TRIGGER knowledge_fts_ai AFTER INSERT ON knowledge_records BEGIN
    INSERT INTO knowledge_fts(rowid, title, content) VALUES (new.rowid, new.title, new.content);
END;
CREATE TRIGGER knowledge_fts_ad AFTER DELETE ON knowledge_records BEGIN
    INSERT INTO knowledge_fts(knowledge_fts, rowid, title, content) VALUES ('delete', old.rowid, old.title, old.content);
END;
-- Only text changes touch the index; embedding/tag refreshes do not.
CREATE TRIGGER knowledge_fts_au AFTER UPDATE OF title, content ON knowledge_records BEGIN
    INSERT INTO knowledge_fts(knowledge_fts, rowid, title, content) VALUES ('delete', old.rowid, old.title, old.content);
    INSERT INTO knowledge_fts(rowid, title, content) VALUES (new.rowid, new.title, new.content);
END;

CREATE TABLE threads (
    id         TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL REFERENCES users(id),
    title      TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE messages (
    id             TEXT PRIMARY KEY,
    thread_id      TEXT NOT NULL REFERENCES threads(id),
    role           TEXT NOT NULL,
    content        TEXT NOT NULL,
    reformed_query TEXT,
    citations      TEXT NOT NULL DEFAULT '[]',
    suggested      TEXT NOT NULL DEFAULT '[]',
    retrieved      INTEGER,
    abstained      INTEGER NOT NULL DEFAULT 0,
    created_at     TEXT NOT NULL
);
CREATE INDEX msg_thread_idx ON messages(thread_id, created_at);

CREATE TABLE sync_runs (
    id                TEXT PRIMARY KEY,
    sync_source_id    TEXT NOT NULL REFERENCES sync_sources(id),
    triggered_by      TEXT REFERENCES users(id),
    started_at        TEXT NOT NULL,
    finished_at       TEXT,
    status            TEXT NOT NULL DEFAULT 'running',
    error_message     TEXT,
    records_inserted  INTEGER NOT NULL DEFAULT 0,
    records_updated   INTEGER NOT NULL DEFAULT 0,
    records_deleted   INTEGER NOT NULL DEFAULT 0,
    records_skipped   INTEGER NOT NULL DEFAULT 0,
    stop_requested_by TEXT REFERENCES users(id),
    stop_requested_at TEXT,
    reread            INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE sync_run_items (
    id          TEXT PRIMARY KEY,
    sync_run_id TEXT NOT NULL REFERENCES sync_runs(id),
    parent_id   TEXT NOT NULL,
    action      TEXT NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX sync_run_items_run_idx ON sync_run_items(sync_run_id);

CREATE TABLE ingest_skips (
    sync_source_id TEXT NOT NULL REFERENCES sync_sources(id),
    item           TEXT NOT NULL,
    reason         TEXT NOT NULL,
    detail         TEXT,
    last_seen_at   TEXT NOT NULL,
    PRIMARY KEY (sync_source_id, item, reason)
);
