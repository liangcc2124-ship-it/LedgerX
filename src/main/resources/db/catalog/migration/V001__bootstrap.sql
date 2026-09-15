CREATE TABLE IF NOT EXISTS catalog_schema_history (
    version INTEGER PRIMARY KEY CHECK (version > 0),
    description TEXT NOT NULL,
    checksum TEXT NOT NULL,
    installed_at TEXT NOT NULL,
    success INTEGER NOT NULL CHECK (success IN (0, 1))
);

CREATE TABLE IF NOT EXISTS profile (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL CHECK (trim(name) <> ''),
    relative_directory TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    last_opened_at TEXT NOT NULL,
    archived_at TEXT NULL,
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0)
);

CREATE TABLE IF NOT EXISTS catalog_setting (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    active_profile_id TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (active_profile_id) REFERENCES profile(id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_profile_archived_last_opened
    ON profile (archived_at, last_opened_at DESC);
