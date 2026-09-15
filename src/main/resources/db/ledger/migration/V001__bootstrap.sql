CREATE TABLE IF NOT EXISTS schema_history (
    version INTEGER PRIMARY KEY CHECK (version > 0),
    description TEXT NOT NULL,
    checksum TEXT NOT NULL,
    installed_at TEXT NOT NULL,
    success INTEGER NOT NULL CHECK (success IN (0, 1))
);

CREATE TABLE IF NOT EXISTS ledger_meta (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    profile_id TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    data_revision INTEGER NOT NULL DEFAULT 0 CHECK (data_revision >= 0),
    source_json_schema INTEGER NULL,
    source_import_hash TEXT NULL UNIQUE,
    import_completed_at TEXT NULL
);

CREATE TABLE IF NOT EXISTS processed_operation (
    idempotency_key TEXT PRIMARY KEY CHECK (length(idempotency_key) = 36),
    http_method TEXT NOT NULL CHECK (http_method IN ('POST', 'PUT', 'PATCH', 'DELETE')),
    canonical_path TEXT NOT NULL CHECK (length(canonical_path) BETWEEN 1 AND 300),
    request_hash TEXT NOT NULL CHECK (length(request_hash) = 64),
    response_status INTEGER NOT NULL CHECK (response_status BETWEEN 200 AND 299),
    response_json TEXT NOT NULL,
    profile_id TEXT NOT NULL,
    completed_at TEXT NOT NULL,
    expires_at TEXT NOT NULL CHECK (expires_at > completed_at)
);

CREATE INDEX IF NOT EXISTS idx_processed_operation_expires_at
    ON processed_operation (expires_at);
