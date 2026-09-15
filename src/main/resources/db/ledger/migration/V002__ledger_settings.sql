CREATE TABLE IF NOT EXISTS ledger_setting (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    notifications_enabled INTEGER NOT NULL DEFAULT 0 CHECK (notifications_enabled IN (0, 1)),
    auto_backup_enabled INTEGER NOT NULL DEFAULT 1 CHECK (auto_backup_enabled IN (0, 1)),
    auto_backup_interval_days INTEGER NOT NULL DEFAULT 7
        CHECK (auto_backup_interval_days BETWEEN 1 AND 365),
    auto_backup_retention_count INTEGER NOT NULL DEFAULT 10
        CHECK (auto_backup_retention_count BETWEEN 1 AND 100),
    last_auto_backup_at TEXT NULL,
    last_settings_section TEXT NOT NULL DEFAULT 'GENERAL'
        CHECK (last_settings_section IN ('GENERAL', 'PROFILES', 'CATEGORIES', 'ACCOUNTS')),
    currency_code TEXT NOT NULL DEFAULT 'CNY' CHECK (currency_code = 'CNY'),
    currency_symbol TEXT NOT NULL DEFAULT '¥' CHECK (currency_symbol = '¥'),
    safety_buffer_minor INTEGER NOT NULL DEFAULT 300000 CHECK (safety_buffer_minor >= 0),
    hide_all_amounts INTEGER NOT NULL DEFAULT 0 CHECK (hide_all_amounts IN (0, 1)),
    theme_name TEXT NOT NULL DEFAULT 'WARM_COPPER'
        CHECK (theme_name IN ('WARM_COPPER', 'GRAPHITE', 'DEEP_SEA_BLUE', 'CUSTOM')),
    custom_theme_css TEXT NULL CHECK (length(custom_theme_css) <= 65536),
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    updated_at TEXT NOT NULL
);

INSERT INTO ledger_setting (id, updated_at)
SELECT 1, COALESCE((SELECT created_at FROM ledger_meta WHERE id = 1), '1970-01-01T00:00:00Z')
WHERE NOT EXISTS (SELECT 1 FROM ledger_setting WHERE id = 1);
