-- Owner's decision 2026-10-13: people log in with a username; email stays as an optional contact
-- address. SQLite can't drop NOT NULL from a column, so users is rebuilt (the procedure from the
-- SQLite docs, "Making Other Kinds Of Table Schema Changes").
--
-- The Migrator runs this with foreign keys off and commits only if PRAGMA foreign_key_check then
-- finds nothing: user_groups / threads / sync_runs rows keep their user ids, which the rebuilt
-- table (renamed to users) still holds.

CREATE TABLE users_new (
    id            TEXT PRIMARY KEY,
    username      TEXT UNIQUE NOT NULL COLLATE NOCASE,
    email         TEXT UNIQUE COLLATE NOCASE,     -- optional, contact only (not used to log in)
    name          TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    active        INTEGER NOT NULL DEFAULT 1,
    is_admin      INTEGER NOT NULL DEFAULT 0,
    deleted_at    TEXT
);

-- Existing users: the username is the part of their email before '@' (admin@... -> admin),
-- lower-cased; a second user with the same part gets '-<n>' added so every username is unique.
INSERT INTO users_new (id, username, email, name, password_hash, active, is_admin, deleted_at)
SELECT u.id,
       lower(substr(u.email, 1, instr(u.email || '@', '@') - 1))
           || CASE WHEN EXISTS (SELECT 1 FROM users o WHERE o.rowid < u.rowid
                                AND lower(substr(o.email, 1, instr(o.email || '@', '@') - 1))
                                  = lower(substr(u.email, 1, instr(u.email || '@', '@') - 1)))
                   THEN '-' || u.rowid ELSE '' END,
       u.email, u.name, u.password_hash, u.active, u.is_admin, u.deleted_at
FROM users u;

DROP TABLE users;
ALTER TABLE users_new RENAME TO users;
