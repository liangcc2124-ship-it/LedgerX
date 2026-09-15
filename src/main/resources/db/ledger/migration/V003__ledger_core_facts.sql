CREATE TABLE IF NOT EXISTS category (
    id TEXT PRIMARY KEY NOT NULL,
    parent_id TEXT NULL,
    name TEXT NOT NULL,
    is_system INTEGER NOT NULL DEFAULT 0 CHECK (is_system IN (0, 1)),
    is_legacy_custom INTEGER NOT NULL DEFAULT 0 CHECK (is_legacy_custom IN (0, 1)),
    archived_at TEXT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    default_recognition_method TEXT NOT NULL DEFAULT 'IMMEDIATE',
    recommended_depreciation_method TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    FOREIGN KEY (parent_id) REFERENCES category(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS category_record_type (
    category_id TEXT NOT NULL,
    record_type TEXT NOT NULL CHECK (record_type IN (
        'INCOME', 'FIXED_COST', 'VARIABLE_COST', 'FIXED_ASSET_PURCHASE',
        'PAYABLE_CREATED', 'PAYABLE_PAYMENT'
    )),
    PRIMARY KEY (category_id, record_type),
    FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS financial_account (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN (
        'CASH', 'BANK', 'WALLET', 'CREDIT', 'LOAN', 'OTHER_ASSET', 'OTHER_LIABILITY'
    )),
    balance_side TEXT NOT NULL CHECK (balance_side IN ('ASSET', 'LIABILITY')),
    opening_on TEXT NOT NULL,
    opening_balance_minor INTEGER NOT NULL,
    include_in_available_cash INTEGER NOT NULL DEFAULT 0 CHECK (include_in_available_cash IN (0, 1)),
    is_system INTEGER NOT NULL DEFAULT 0 CHECK (is_system IN (0, 1)),
    archived_at TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    CHECK (balance_side = 'ASSET' OR include_in_available_cash = 0)
);

CREATE TABLE IF NOT EXISTS finance_record (
    id TEXT PRIMARY KEY NOT NULL,
    occurred_on TEXT NOT NULL,
    record_type TEXT NOT NULL CHECK (record_type IN (
        'INCOME', 'FIXED_COST', 'VARIABLE_COST', 'FIXED_ASSET_PURCHASE',
        'PAYABLE_CREATED', 'PAYABLE_PAYMENT'
    )),
    amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
    currency_code TEXT NOT NULL DEFAULT 'CNY' CHECK (currency_code = 'CNY'),
    category_id TEXT NULL,
    account_id TEXT NULL,
    settlement_mode TEXT NOT NULL,
    settlement_on TEXT NULL,
    income_source TEXT NULL,
    is_self_generated_income INTEGER NOT NULL DEFAULT 0 CHECK (is_self_generated_income IN (0, 1)),
    is_non_essential INTEGER NOT NULL DEFAULT 0 CHECK (is_non_essential IN (0, 1)),
    note TEXT NOT NULL DEFAULT '' CHECK (length(note) <= 4000),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    deleted_at TEXT NULL,
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE RESTRICT,
    FOREIGN KEY (account_id) REFERENCES financial_account(id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_category_parent_archived_sort
    ON category (parent_id, archived_at, sort_order);

CREATE INDEX IF NOT EXISTS idx_category_record_type_record_type_category
    ON category_record_type (record_type, category_id);

CREATE INDEX IF NOT EXISTS idx_financial_account_archived_name
    ON financial_account (archived_at, name COLLATE NOCASE);

CREATE INDEX IF NOT EXISTS idx_finance_record_deleted_occurred_id
    ON finance_record (deleted_at, occurred_on DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_finance_record_account_deleted_occurred
    ON finance_record (account_id, deleted_at, occurred_on);

CREATE INDEX IF NOT EXISTS idx_finance_record_category_deleted_occurred
    ON finance_record (category_id, deleted_at, occurred_on);

CREATE INDEX IF NOT EXISTS idx_finance_record_type_deleted_occurred
    ON finance_record (record_type, deleted_at, occurred_on);

CREATE INDEX IF NOT EXISTS idx_finance_record_settlement_deleted
    ON finance_record (settlement_on, deleted_at);

CREATE UNIQUE INDEX IF NOT EXISTS ux_category_active_top_name
    ON category (name COLLATE NOCASE)
    WHERE parent_id IS NULL AND archived_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_category_active_parent_name
    ON category (parent_id, name COLLATE NOCASE)
    WHERE parent_id IS NOT NULL AND archived_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_financial_account_active_name
    ON financial_account (name COLLATE NOCASE)
    WHERE archived_at IS NULL;
