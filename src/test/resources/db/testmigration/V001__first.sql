CREATE TABLE test_schema_history (
    version INTEGER PRIMARY KEY,
    description TEXT NOT NULL,
    checksum TEXT NOT NULL,
    installed_at TEXT NOT NULL,
    success INTEGER NOT NULL
);

CREATE TABLE migration_probe (
    id INTEGER PRIMARY KEY,
    value TEXT NOT NULL
);
