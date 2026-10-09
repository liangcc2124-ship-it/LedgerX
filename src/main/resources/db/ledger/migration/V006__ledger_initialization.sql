ALTER TABLE ledger_setting ADD COLUMN setup_state TEXT NOT NULL DEFAULT 'PENDING'
    CHECK (setup_state IN ('PENDING', 'REVIEW_REQUIRED', 'COMPLETED'));

ALTER TABLE ledger_setting ADD COLUMN ledger_start_on TEXT NULL;

ALTER TABLE ledger_setting ADD COLUMN setup_completed_at TEXT NULL;
