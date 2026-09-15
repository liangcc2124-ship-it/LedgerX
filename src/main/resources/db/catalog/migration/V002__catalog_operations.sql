ALTER TABLE catalog_setting
    ADD COLUMN revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0);

CREATE TABLE catalog_processed_operation (
    idempotency_key TEXT PRIMARY KEY CHECK (length(idempotency_key) = 36),
    http_method TEXT NOT NULL CHECK (http_method IN ('POST', 'DELETE')),
    canonical_path TEXT NOT NULL CHECK (length(canonical_path) BETWEEN 1 AND 300),
    request_hash TEXT NOT NULL CHECK (length(request_hash) = 64),
    response_status INTEGER NOT NULL CHECK (response_status BETWEEN 200 AND 299),
    response_json TEXT NOT NULL,
    etag TEXT NULL,
    location TEXT NULL,
    completed_at TEXT NOT NULL,
    expires_at TEXT NOT NULL CHECK (expires_at > completed_at)
);

CREATE INDEX idx_catalog_processed_operation_expires_at
    ON catalog_processed_operation (expires_at);
