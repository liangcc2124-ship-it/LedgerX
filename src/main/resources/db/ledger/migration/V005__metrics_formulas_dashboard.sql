CREATE TABLE formula_definition (
    id TEXT PRIMARY KEY NOT NULL,
    scope TEXT NOT NULL CHECK (scope = 'METRIC'),
    result_type TEXT NOT NULL CHECK (result_type IN ('CURRENCY','PERCENT','NUMBER','INTEGER')),
    is_template INTEGER NOT NULL DEFAULT 0 CHECK (is_template IN (0,1)),
    archived_at TEXT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE metric_definition (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 100),
    description TEXT NOT NULL DEFAULT '' CHECK (length(description) <= 500),
    display_format TEXT NOT NULL CHECK (display_format IN ('CURRENCY','PERCENT','NUMBER','INTEGER')),
    precision INTEGER NOT NULL CHECK (precision BETWEEN 0 AND 8),
    period_behavior TEXT NOT NULL CHECK (period_behavior IN ('PERIOD','AS_OF','MIXED')),
    current_formula_version_id TEXT NULL REFERENCES formula_version(id) ON DELETE RESTRICT,
    is_system INTEGER NOT NULL CHECK (is_system IN (0,1)),
    archived_at TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    CHECK ((display_format <> 'CURRENCY' OR precision = 2)
        AND (display_format <> 'INTEGER' OR precision = 0))
);

CREATE TABLE formula_version (
    id TEXT PRIMARY KEY NOT NULL,
    formula_id TEXT NOT NULL REFERENCES formula_definition(id) ON DELETE RESTRICT,
    version INTEGER NOT NULL CHECK (version >= 1),
    ast_json TEXT NOT NULL CHECK (length(ast_json) BETWEEN 2 AND 65536),
    tokens_json TEXT NOT NULL CHECK (length(tokens_json) BETWEEN 2 AND 65536),
    created_at TEXT NOT NULL,
    UNIQUE (formula_id, version)
);

CREATE TABLE formula_dependency (
    formula_version_id TEXT NOT NULL REFERENCES formula_version(id) ON DELETE CASCADE,
    dependency_kind TEXT NOT NULL CHECK (dependency_kind IN ('METRIC','CATEGORY_INCOME','CATEGORY_EXPENSE','ACCOUNT_BALANCE','TIME')),
    dependency_key TEXT NOT NULL CHECK (length(dependency_key) BETWEEN 1 AND 120),
    referenced_metric_id TEXT NULL REFERENCES metric_definition(id) ON DELETE RESTRICT,
    referenced_category_id TEXT NULL REFERENCES category(id) ON DELETE RESTRICT,
    referenced_account_id TEXT NULL REFERENCES financial_account(id) ON DELETE RESTRICT,
    PRIMARY KEY (formula_version_id, dependency_kind, dependency_key),
    CHECK (
        (dependency_kind = 'METRIC' AND referenced_metric_id IS NOT NULL AND referenced_category_id IS NULL AND referenced_account_id IS NULL)
        OR (dependency_kind IN ('CATEGORY_INCOME','CATEGORY_EXPENSE') AND referenced_metric_id IS NULL AND referenced_category_id IS NOT NULL AND referenced_account_id IS NULL)
        OR (dependency_kind = 'ACCOUNT_BALANCE' AND referenced_metric_id IS NULL AND referenced_category_id IS NULL AND referenced_account_id IS NOT NULL)
        OR (dependency_kind = 'TIME' AND referenced_metric_id IS NULL AND referenced_category_id IS NULL AND referenced_account_id IS NULL)
    )
);

CREATE TABLE metric_visibility (
    metric_id TEXT PRIMARY KEY NOT NULL REFERENCES metric_definition(id) ON DELETE CASCADE,
    hidden INTEGER NOT NULL DEFAULT 0 CHECK (hidden IN (0,1)),
    dashboard_enabled INTEGER NOT NULL DEFAULT 0 CHECK (dashboard_enabled IN (0,1)),
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    updated_at TEXT NOT NULL
);

CREATE TABLE dashboard_layout (
    id TEXT PRIMARY KEY NOT NULL,
    view_key TEXT NOT NULL CHECK (view_key = 'financial-overview'),
    breakpoint TEXT NOT NULL CHECK (breakpoint = 'desktop'),
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    updated_at TEXT NOT NULL,
    UNIQUE (view_key, breakpoint)
);

CREATE TABLE dashboard_layout_item (
    layout_id TEXT NOT NULL REFERENCES dashboard_layout(id) ON DELETE CASCADE,
    widget_id TEXT NOT NULL,
    x INTEGER NOT NULL CHECK (x BETWEEN 0 AND 11),
    y INTEGER NOT NULL CHECK (y >= 0),
    w INTEGER NOT NULL CHECK (w BETWEEN 1 AND 12),
    h INTEGER NOT NULL CHECK (h >= 1),
    min_w INTEGER NOT NULL CHECK (min_w BETWEEN 1 AND 12),
    min_h INTEGER NOT NULL CHECK (min_h >= 1),
    max_w INTEGER NOT NULL CHECK (max_w BETWEEN 1 AND 12),
    max_h INTEGER NOT NULL CHECK (max_h >= 1),
    PRIMARY KEY (layout_id, widget_id),
    CHECK (x + w <= 12),
    CHECK (min_w <= w AND w <= max_w),
    CHECK (min_h <= h AND h <= max_h)
);

CREATE UNIQUE INDEX ux_metric_definition_active_name
    ON metric_definition(name COLLATE NOCASE) WHERE archived_at IS NULL;
CREATE INDEX idx_formula_version_formula_version_desc
    ON formula_version(formula_id, version DESC);
CREATE INDEX idx_formula_dependency_metric ON formula_dependency(referenced_metric_id);
CREATE INDEX idx_formula_dependency_category ON formula_dependency(referenced_category_id);
CREATE INDEX idx_formula_dependency_account ON formula_dependency(referenced_account_id);
CREATE INDEX idx_metric_definition_active_name
    ON metric_definition(archived_at, name COLLATE NOCASE);

INSERT INTO metric_definition
    (id, name, description, display_format, precision, period_behavior, current_formula_version_id,
     is_system, archived_at, created_at, updated_at, revision)
VALUES
    ('income', '收入', '统计期间内按发生日确认的收入。', 'CURRENCY', 2, 'PERIOD', NULL, 1, NULL, strftime('%Y-%m-%dT%H:%M:%fZ','now'), strftime('%Y-%m-%dT%H:%M:%fZ','now'), 0),
    ('fixed-expense', '固定支出', '统计期间内按发生日确认的固定支出。', 'CURRENCY', 2, 'PERIOD', NULL, 1, NULL, strftime('%Y-%m-%dT%H:%M:%fZ','now'), strftime('%Y-%m-%dT%H:%M:%fZ','now'), 0),
    ('variable-expense', '弹性支出', '统计期间内按发生日确认的弹性支出。', 'CURRENCY', 2, 'PERIOD', NULL, 1, NULL, strftime('%Y-%m-%dT%H:%M:%fZ','now'), strftime('%Y-%m-%dT%H:%M:%fZ','now'), 0),
    ('total-expense', '总支出', '固定支出与弹性支出的合计。', 'CURRENCY', 2, 'PERIOD', NULL, 1, NULL, strftime('%Y-%m-%dT%H:%M:%fZ','now'), strftime('%Y-%m-%dT%H:%M:%fZ','now'), 0),
    ('net-result', '净收支', '收入减去总支出。', 'CURRENCY', 2, 'PERIOD', NULL, 1, NULL, strftime('%Y-%m-%dT%H:%M:%fZ','now'), strftime('%Y-%m-%dT%H:%M:%fZ','now'), 0),
    ('savings-rate', '储蓄率', '净收支占收入的比例。', 'PERCENT', 1, 'PERIOD', NULL, 1, NULL, strftime('%Y-%m-%dT%H:%M:%fZ','now'), strftime('%Y-%m-%dT%H:%M:%fZ','now'), 0),
    ('cash-inflow', '现金流入', '按结算日确认的现金流入。', 'CURRENCY', 2, 'PERIOD', NULL, 1, NULL, strftime('%Y-%m-%dT%H:%M:%fZ','now'), strftime('%Y-%m-%dT%H:%M:%fZ','now'), 0),
    ('cash-outflow', '现金流出', '按结算日确认的现金流出。', 'CURRENCY', 2, 'PERIOD', NULL, 1, NULL, strftime('%Y-%m-%dT%H:%M:%fZ','now'), strftime('%Y-%m-%dT%H:%M:%fZ','now'), 0),
    ('net-cash-flow', '净现金流', '现金流入减去现金流出。', 'CURRENCY', 2, 'PERIOD', NULL, 1, NULL, strftime('%Y-%m-%dT%H:%M:%fZ','now'), strftime('%Y-%m-%dT%H:%M:%fZ','now'), 0),
    ('available-cash', '可用现金', '当前可用于支出的现金余额。', 'CURRENCY', 2, 'AS_OF', NULL, 1, NULL, strftime('%Y-%m-%dT%H:%M:%fZ','now'), strftime('%Y-%m-%dT%H:%M:%fZ','now'), 0),
    ('total-assets', '资产合计', '截至期间 asOf 的资产余额。', 'CURRENCY', 2, 'AS_OF', NULL, 1, NULL, strftime('%Y-%m-%dT%H:%M:%fZ','now'), strftime('%Y-%m-%dT%H:%M:%fZ','now'), 0),
    ('total-liabilities', '负债合计', '截至期间 asOf 的负债余额。', 'CURRENCY', 2, 'AS_OF', NULL, 1, NULL, strftime('%Y-%m-%dT%H:%M:%fZ','now'), strftime('%Y-%m-%dT%H:%M:%fZ','now'), 0),
    ('net-assets', '净资产', '资产合计减去负债合计。', 'CURRENCY', 2, 'AS_OF', NULL, 1, NULL, strftime('%Y-%m-%dT%H:%M:%fZ','now'), strftime('%Y-%m-%dT%H:%M:%fZ','now'), 0),
    ('average-daily-expense', '日均支出', '统计期间内每天的平均支出。', 'CURRENCY', 2, 'PERIOD', NULL, 1, NULL, strftime('%Y-%m-%dT%H:%M:%fZ','now'), strftime('%Y-%m-%dT%H:%M:%fZ','now'), 0),
    ('fixed-expense-ratio', '固定支出占比', '固定支出占总支出的比例。', 'PERCENT', 1, 'PERIOD', NULL, 1, NULL, strftime('%Y-%m-%dT%H:%M:%fZ','now'), strftime('%Y-%m-%dT%H:%M:%fZ','now'), 0),
    ('variable-expense-ratio', '弹性支出占比', '弹性支出占总支出的比例。', 'PERCENT', 1, 'PERIOD', NULL, 1, NULL, strftime('%Y-%m-%dT%H:%M:%fZ','now'), strftime('%Y-%m-%dT%H:%M:%fZ','now'), 0),
    ('transaction-count', '记录笔数', '统计期间内的有效记录数。', 'INTEGER', 0, 'PERIOD', NULL, 1, NULL, strftime('%Y-%m-%dT%H:%M:%fZ','now'), strftime('%Y-%m-%dT%H:%M:%fZ','now'), 0);

INSERT INTO metric_visibility (metric_id, hidden, dashboard_enabled, revision, updated_at)
SELECT id, 0,
       CASE WHEN id IN ('income','total-expense','net-result','savings-rate','fixed-expense','variable-expense','available-cash','net-assets') THEN 1 ELSE 0 END,
       0, strftime('%Y-%m-%dT%H:%M:%fZ','now')
FROM metric_definition;

INSERT INTO dashboard_layout (id, view_key, breakpoint, revision, updated_at)
VALUES ('00000000-0000-0000-0000-000000000005', 'financial-overview', 'desktop', 0, strftime('%Y-%m-%dT%H:%M:%fZ','now'));

INSERT INTO dashboard_layout_item
    (layout_id, widget_id, x, y, w, h, min_w, min_h, max_w, max_h)
VALUES
    ('00000000-0000-0000-0000-000000000005', 'metric:income', 0, 0, 3, 2, 3, 2, 12, 6),
    ('00000000-0000-0000-0000-000000000005', 'metric:total-expense', 3, 0, 3, 2, 3, 2, 12, 6),
    ('00000000-0000-0000-0000-000000000005', 'metric:net-result', 6, 0, 3, 2, 3, 2, 12, 6),
    ('00000000-0000-0000-0000-000000000005', 'metric:savings-rate', 9, 0, 3, 2, 3, 2, 12, 6),
    ('00000000-0000-0000-0000-000000000005', 'metric:fixed-expense', 0, 2, 3, 2, 3, 2, 12, 6),
    ('00000000-0000-0000-0000-000000000005', 'metric:variable-expense', 3, 2, 3, 2, 3, 2, 12, 6),
    ('00000000-0000-0000-0000-000000000005', 'metric:available-cash', 6, 2, 3, 2, 3, 2, 12, 6),
    ('00000000-0000-0000-0000-000000000005', 'metric:net-assets', 9, 2, 3, 2, 3, 2, 12, 6);
