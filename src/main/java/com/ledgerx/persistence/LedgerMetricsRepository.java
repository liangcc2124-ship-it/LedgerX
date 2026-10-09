package com.ledgerx.persistence;

import com.ledgerx.application.metrics.ResolvedPeriod;
import com.ledgerx.application.metrics.SystemMetricFacts;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SQL boundary for metric definitions, dashboard layout and bounded fact aggregation. */
public final class LedgerMetricsRepository {
    private static final BigDecimal CENT = BigDecimal.valueOf(100);
    private final Path ledgerFile;

    public LedgerMetricsRepository(Path ledgerFile) {
        if (ledgerFile == null) throw new IllegalArgumentException("ledgerFile is required");
        this.ledgerFile = ledgerFile.toAbsolutePath().normalize();
    }

    public Connection openConnection() throws PersistenceException {
        try {
            return new SqliteDatabase(ledgerFile).open();
        } catch (IOException | SQLException ex) {
            throw new PersistenceException("ledger database could not be opened", ex);
        }
    }

    public List<MetricDefinitionRecord> listMetricDefinitions(Connection connection, boolean includeArchived)
            throws SQLException {
        String sql = "SELECT id,name,description,display_format,precision,period_behavior,current_formula_version_id,"
                + "is_system,archived_at,created_at,updated_at,revision FROM metric_definition "
                + "WHERE (? = 1 OR archived_at IS NULL) "
                + "ORDER BY is_system DESC,name COLLATE NOCASE,id";
        List<MetricDefinitionRecord> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, includeArchived ? 1 : 0);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(readMetric(rows));
            }
        }
        return result;
    }

    public MetricDefinitionRecord findMetric(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,name,description,display_format,precision,period_behavior,current_formula_version_id,"
                        + "is_system,archived_at,created_at,updated_at,revision FROM metric_definition WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readMetric(rows) : null;
            }
        }
    }

    public VisibilityRecord findVisibility(Connection connection, String metricId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT metric_id,hidden,dashboard_enabled,revision,updated_at FROM metric_visibility WHERE metric_id=?")) {
            statement.setString(1, metricId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? new VisibilityRecord(rows.getString("metric_id"), rows.getInt("hidden") == 1,
                        rows.getInt("dashboard_enabled") == 1, rows.getLong("revision"), rows.getString("updated_at")) : null;
            }
        }
    }

    public List<VisibilityRecord> listVisibility(Connection connection) throws SQLException {
        List<VisibilityRecord> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT metric_id,hidden,dashboard_enabled,revision,updated_at FROM metric_visibility ORDER BY metric_id");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(new VisibilityRecord(rows.getString("metric_id"), rows.getInt("hidden") == 1,
                    rows.getInt("dashboard_enabled") == 1, rows.getLong("revision"), rows.getString("updated_at")));
        }
        return result;
    }

    public void insertFormulaDefinition(Connection connection, String id, String resultType, String now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO formula_definition(id,scope,result_type,is_template,archived_at,created_at) "
                        + "VALUES (?, 'METRIC', ?, 0, NULL, ?)")) {
            statement.setString(1, id); statement.setString(2, resultType); statement.setString(3, now);
            statement.executeUpdate();
        }
    }

    public void insertMetricDefinition(Connection connection, String id, String name, String description,
            String displayFormat, int precision, String periodBehavior, boolean system, String now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO metric_definition(id,name,description,display_format,precision,period_behavior,"
                        + "current_formula_version_id,is_system,archived_at,created_at,updated_at,revision) "
                        + "VALUES (?,?,?,?,?,?,NULL,?,NULL,?,?,0)")) {
            statement.setString(1, id); statement.setString(2, name); statement.setString(3, description);
            statement.setString(4, displayFormat); statement.setInt(5, precision); statement.setString(6, periodBehavior);
            statement.setInt(7, system ? 1 : 0); statement.setString(8, now); statement.setString(9, now);
            statement.executeUpdate();
        }
        try (PreparedStatement visibility = connection.prepareStatement(
                "INSERT INTO metric_visibility(metric_id,hidden,dashboard_enabled,revision,updated_at) VALUES(?,0,0,0,?)")) {
            visibility.setString(1, id); visibility.setString(2, now); visibility.executeUpdate();
        }
    }

    public void insertFormulaVersion(Connection connection, String id, String formulaId, int version,
            String astJson, String tokensJson, String now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO formula_version(id,formula_id,version,ast_json,tokens_json,created_at) VALUES(?,?,?,?,?,?)")) {
            statement.setString(1, id); statement.setString(2, formulaId); statement.setInt(3, version);
            statement.setString(4, astJson); statement.setString(5, tokensJson); statement.setString(6, now);
            statement.executeUpdate();
        }
    }

    public void setCurrentFormulaVersion(Connection connection, String metricId, String versionId, long revision,
            String now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE metric_definition SET current_formula_version_id=?,updated_at=?,revision=? WHERE id=?")) {
            statement.setString(1, versionId); statement.setString(2, now); statement.setLong(3, revision);
            statement.setString(4, metricId);
            if (statement.executeUpdate() != 1) throw new SQLException("metric definition was not found");
        }
    }

    public int nextFormulaVersion(Connection connection, String formulaId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(version),0)+1 FROM formula_version WHERE formula_id=?")) {
            statement.setString(1, formulaId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    public FormulaVersionRecord findFormulaVersion(Connection connection, String versionId) throws SQLException {
        if (versionId == null) return null;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,formula_id,version,ast_json,tokens_json,created_at FROM formula_version WHERE id=?")) {
            statement.setString(1, versionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new FormulaVersionRecord(rows.getString("id"), rows.getString("formula_id"),
                        rows.getInt("version"), rows.getString("ast_json"), rows.getString("tokens_json"),
                        rows.getString("created_at"));
            }
        }
    }

    public List<FormulaVersionRecord> listFormulaVersions(Connection connection, String formulaId, int limit)
            throws SQLException {
        List<FormulaVersionRecord> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,formula_id,version,ast_json,tokens_json,created_at FROM formula_version "
                        + "WHERE formula_id=? ORDER BY version DESC LIMIT ?")) {
            statement.setString(1, formulaId); statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new FormulaVersionRecord(rows.getString("id"),
                        rows.getString("formula_id"), rows.getInt("version"), rows.getString("ast_json"),
                        rows.getString("tokens_json"), rows.getString("created_at")));
            }
        }
        return result;
    }

    /** Returns the immutable dependency rows for one formula version in stable display order. */
    public List<DependencyRecord> listDependencies(Connection connection, String versionId) throws SQLException {
        List<DependencyRecord> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT dependency_kind,dependency_key,referenced_metric_id,referenced_category_id,referenced_account_id "
                        + "FROM formula_dependency WHERE formula_version_id=? ORDER BY dependency_kind,dependency_key")) {
            statement.setString(1, versionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new DependencyRecord(rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getString(4), rows.getString(5)));
            }
        }
        return result;
    }

    /** Current active custom-metric graph used only for candidate cycle validation. */
    public Map<String, java.util.Set<String>> activeMetricDependencyGraph(Connection connection) throws SQLException {
        Map<String, java.util.Set<String>> graph = new LinkedHashMap<>();
        String sql = "SELECT m.id,d.referenced_metric_id FROM metric_definition m "
                + "LEFT JOIN formula_dependency d ON d.formula_version_id=m.current_formula_version_id "
                + "AND d.dependency_kind='METRIC' "
                + "WHERE m.archived_at IS NULL AND m.is_system=0 ORDER BY m.id,d.referenced_metric_id";
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String metricId = rows.getString(1);
                java.util.Set<String> references = graph.get(metricId);
                if (references == null) {
                    references = new java.util.LinkedHashSet<>();
                    graph.put(metricId, references);
                }
                String reference = rows.getString(2);
                if (reference != null) references.add(reference);
            }
        }
        return graph;
    }

    public void updateMetricDefinition(Connection connection, String id, String name, String description,
            String displayFormat, int precision, String periodBehavior, long revision, String now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE metric_definition SET name=?,description=?,display_format=?,precision=?,period_behavior=?,"
                        + "updated_at=?,revision=? WHERE id=?")) {
            statement.setString(1, name); statement.setString(2, description); statement.setString(3, displayFormat);
            statement.setInt(4, precision); statement.setString(5, periodBehavior); statement.setString(6, now);
            statement.setLong(7, revision); statement.setString(8, id);
            if (statement.executeUpdate() != 1) throw new SQLException("metric definition was not found");
        }
    }

    public void archiveMetric(Connection connection, String id, long revision, String now) throws SQLException {
        try (PreparedStatement metric = connection.prepareStatement(
                "UPDATE metric_definition SET archived_at=?,updated_at=?,revision=? WHERE id=? AND archived_at IS NULL");
                PreparedStatement visibility = connection.prepareStatement(
                        "UPDATE metric_visibility SET dashboard_enabled=0,revision=revision+1,updated_at=? WHERE metric_id=?")) {
            metric.setString(1, now); metric.setString(2, now); metric.setLong(3, revision); metric.setString(4, id);
            if (metric.executeUpdate() != 1) throw new SQLException("metric definition was not active");
            visibility.setString(1, now); visibility.setString(2, id); visibility.executeUpdate();
        }
    }

    public List<String> findMetricReferences(Connection connection, String referencedMetricId) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT DISTINCT m.id FROM metric_definition m "
                        + "JOIN formula_version v ON v.id=m.current_formula_version_id "
                        + "JOIN formula_dependency d ON d.formula_version_id=v.id "
                        + "WHERE m.archived_at IS NULL AND d.dependency_kind='METRIC' "
                        + "AND d.referenced_metric_id=? ORDER BY m.id")) {
            statement.setString(1, referencedMetricId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
        }
        return result;
    }

    public void insertDependencies(Connection connection, String versionId, List<DependencyRecord> dependencies)
            throws SQLException {
        if (dependencies == null) return;
        String sql = "INSERT INTO formula_dependency(formula_version_id,dependency_kind,dependency_key,"
                + "referenced_metric_id,referenced_category_id,referenced_account_id) VALUES(?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (DependencyRecord dependency : dependencies) {
                statement.setString(1, versionId); statement.setString(2, dependency.kind);
                statement.setString(3, dependency.key); setNullable(statement, 4, dependency.metricId);
                setNullable(statement, 5, dependency.categoryId); setNullable(statement, 6, dependency.accountId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public void updateVisibility(Connection connection, String metricId, boolean hidden, boolean dashboardEnabled,
            long visibilityRevision, long metricRevision, String now) throws SQLException {
        try (PreparedStatement visibility = connection.prepareStatement(
                "UPDATE metric_visibility SET hidden=?,dashboard_enabled=?,revision=?,updated_at=? WHERE metric_id=?");
                PreparedStatement metric = connection.prepareStatement(
                        "UPDATE metric_definition SET updated_at=?,revision=? WHERE id=?")) {
            visibility.setInt(1, hidden ? 1 : 0); visibility.setInt(2, dashboardEnabled ? 1 : 0);
            visibility.setLong(3, visibilityRevision); visibility.setString(4, now); visibility.setString(5, metricId);
            if (visibility.executeUpdate() != 1) throw new SQLException("metric visibility was not found");
            metric.setString(1, now); metric.setLong(2, metricRevision); metric.setString(3, metricId);
            if (metric.executeUpdate() != 1) throw new SQLException("metric definition was not found");
        }
    }

    public void replaceLayout(Connection connection, String layoutId, long expectedRevision,
            List<LayoutItemRecord> items, String now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE dashboard_layout SET revision=revision+1,updated_at=? WHERE id=? AND revision=?")) {
            update.setString(1, now); update.setString(2, layoutId); update.setLong(3, expectedRevision);
            if (update.executeUpdate() != 1) throw new SQLException("layout revision conflict");
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM dashboard_layout_item WHERE layout_id=?");
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO dashboard_layout_item(layout_id,widget_id,x,y,w,h,min_w,min_h,max_w,max_h) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            delete.setString(1, layoutId); delete.executeUpdate();
            for (LayoutItemRecord item : items) {
                insert.setString(1, layoutId); insert.setString(2, item.widgetId); insert.setInt(3, item.x);
                insert.setInt(4, item.y); insert.setInt(5, item.w); insert.setInt(6, item.h);
                insert.setInt(7, item.minW); insert.setInt(8, item.minH); insert.setInt(9, item.maxW); insert.setInt(10, item.maxH);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void setNullable(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.VARCHAR); else statement.setString(index, value);
    }

    public LayoutRecord readLayout(Connection connection) throws SQLException {
        LayoutRecord layout = null;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,view_key,breakpoint,revision,updated_at FROM dashboard_layout "
                        + "WHERE view_key='financial-overview' AND breakpoint='desktop'")) {
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    layout = new LayoutRecord(rows.getString("id"), rows.getString("view_key"),
                            rows.getString("breakpoint"), rows.getLong("revision"), rows.getString("updated_at"));
                }
            }
        }
        if (layout == null) return null;
        List<LayoutItemRecord> items = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT widget_id,x,y,w,h,min_w,min_h,max_w,max_h FROM dashboard_layout_item "
                        + "WHERE layout_id=? ORDER BY y,x,widget_id")) {
            statement.setString(1, layout.id);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) items.add(new LayoutItemRecord(rows.getString("widget_id"), rows.getInt("x"),
                        rows.getInt("y"), rows.getInt("w"), rows.getInt("h"), rows.getInt("min_w"),
                        rows.getInt("min_h"), rows.getInt("max_w"), rows.getInt("max_h")));
            }
        }
        return layout.withItems(items);
    }

    public MetricFactsRecord aggregateFacts(Connection connection, ResolvedPeriod period) throws SQLException {
        if (period == null) throw new IllegalArgumentException("period is required");
        String from = period.getStart().toString();
        String to = period.getEndExclusive().toString();
        BigDecimal[] flow = queryFlow(connection, from, to);
        BigDecimal[] cash = queryCash(connection, from, to);
        CategoryFacts categoryFacts = queryCategoryFacts(connection, from, to);
        long count = flow[3].longValueExact();
        SystemMetricFacts.Builder factsBuilder = SystemMetricFacts.builder()
                .income(flow[0]).fixedExpense(flow[1]).variableExpense(flow[2])
                .cashInflow(cash[0]).cashOutflow(cash[1])
                .transactionCount(count)
                .periodDays((int) (period.getAsOf() == null ? 1
                        : java.time.temporal.ChronoUnit.DAYS.between(period.getStart(), period.getAsOf()) + 1))
                .hasPeriodRecords(count > 0)
                ;
        Map<String, BigDecimal> accounts = Collections.emptyMap();
        if (period.getAsOf() != null) {
            accounts = applyAccountFacts(connection, period.getAsOf(), factsBuilder);
        }
        return new MetricFactsRecord(period, factsBuilder.build(), categoryFacts.income, categoryFacts.expense,
                accounts, dataRevision(connection));
    }

    private BigDecimal[] queryFlow(Connection connection, String from, String to) throws SQLException {
        String sql = "SELECT COALESCE(SUM(CASE WHEN record_type='INCOME' THEN amount_minor ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN record_type='FIXED_COST' THEN amount_minor ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN record_type='VARIABLE_COST' THEN amount_minor ELSE 0 END),0),"
                + "COUNT(*) FROM finance_record WHERE deleted_at IS NULL AND record_type IN ('INCOME','FIXED_COST','VARIABLE_COST') "
                + "AND occurred_on >= ? AND occurred_on < ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, from); statement.setString(2, to);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return new BigDecimal[]{minor(rows.getLong(1)), minor(rows.getLong(2)), minor(rows.getLong(3)),
                        BigDecimal.valueOf(rows.getLong(4))};
            }
        }
    }

    private BigDecimal[] queryCash(Connection connection, String from, String to) throws SQLException {
        String sql = "SELECT COALESCE(SUM(CASE WHEN record_type='INCOME' THEN amount_minor ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN record_type IN ('FIXED_COST','VARIABLE_COST') THEN amount_minor ELSE 0 END),0) "
                + "FROM finance_record WHERE deleted_at IS NULL AND settlement_mode='PAID_FROM_ACCOUNT' "
                + "AND record_type IN ('INCOME','FIXED_COST','VARIABLE_COST') AND settlement_on >= ? AND settlement_on < ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, from); statement.setString(2, to);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return new BigDecimal[]{minor(rows.getLong(1)), minor(rows.getLong(2))};
            }
        }
    }

    private CategoryFacts queryCategoryFacts(Connection connection, String from, String to) throws SQLException {
        String sql = "WITH RECURSIVE category_tree(root_id,category_id) AS ("
                + "SELECT id,id FROM category UNION ALL "
                + "SELECT category_tree.root_id,c.id FROM category c "
                + "JOIN category_tree ON c.parent_id=category_tree.category_id) "
                + "SELECT category_tree.root_id,"
                + "COALESCE(SUM(CASE WHEN f.record_type='INCOME' THEN f.amount_minor ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN f.record_type IN ('FIXED_COST','VARIABLE_COST') THEN f.amount_minor ELSE 0 END),0) "
                + "FROM category_tree LEFT JOIN finance_record f ON f.category_id=category_tree.category_id "
                + "AND f.deleted_at IS NULL AND f.occurred_on>=? AND f.occurred_on<? "
                + "AND f.record_type IN ('INCOME','FIXED_COST','VARIABLE_COST') "
                + "GROUP BY category_tree.root_id";
        Map<String, BigDecimal> income = new HashMap<>();
        Map<String, BigDecimal> expense = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, from); statement.setString(2, to);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    income.put(rows.getString(1), minor(rows.getLong(2)));
                    expense.put(rows.getString(1), minor(rows.getLong(3)));
                }
            }
        }
        return new CategoryFacts(income, expense);
    }

    private Map<String, BigDecimal> applyAccountFacts(Connection connection, java.time.LocalDate asOf,
            SystemMetricFacts.Builder builder)
            throws SQLException {
        List<LedgerCatalogRepository.AccountRecord> accounts = new LedgerCatalogRepository(ledgerFile)
                .listAccounts(connection, true, asOf);
        BigDecimal assets = BigDecimal.ZERO;
        BigDecimal liabilities = BigDecimal.ZERO;
        BigDecimal available = BigDecimal.ZERO;
        Map<String, BigDecimal> balances = new HashMap<>();
        for (LedgerCatalogRepository.AccountRecord account : accounts) {
            BigDecimal balance = minor(account.balanceMinor);
            balances.put(account.id, balance);
            if ("LIABILITY".equals(account.balanceSide)) liabilities = liabilities.add(balance);
            else assets = assets.add(balance);
            if (account.archivedAt == null && account.includeInAvailableCash && "ASSET".equals(account.balanceSide)) {
                available = available.add(balance);
            }
        }
        builder.availableCash(available).totalAssets(assets).totalLiabilities(liabilities);
        return Collections.unmodifiableMap(balances);
    }

    private long dataRevision(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT data_revision FROM ledger_meta WHERE id=1");
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    private static BigDecimal minor(long value) {
        return BigDecimal.valueOf(value).divide(CENT, 2, RoundingMode.UNNECESSARY);
    }

    private static MetricDefinitionRecord readMetric(ResultSet rows) throws SQLException {
        return new MetricDefinitionRecord(rows.getString("id"), rows.getString("name"), rows.getString("description"),
                rows.getString("display_format"), rows.getInt("precision"), rows.getString("period_behavior"),
                rows.getString("current_formula_version_id"), rows.getInt("is_system") == 1,
                rows.getString("archived_at"), rows.getString("created_at"), rows.getString("updated_at"),
                rows.getLong("revision"));
    }

    public static final class MetricDefinitionRecord {
        public final String id, name, description, displayFormat, periodBehavior, currentFormulaVersionId;
        public final int precision;
        public final boolean system;
        public final String archivedAt, createdAt, updatedAt;
        public final long revision;

        private MetricDefinitionRecord(String id, String name, String description, String displayFormat, int precision,
                String periodBehavior, String currentFormulaVersionId, boolean system, String archivedAt,
                String createdAt, String updatedAt, long revision) {
            this.id = id; this.name = name; this.description = description; this.displayFormat = displayFormat;
            this.precision = precision; this.periodBehavior = periodBehavior;
            this.currentFormulaVersionId = currentFormulaVersionId; this.system = system; this.archivedAt = archivedAt;
            this.createdAt = createdAt; this.updatedAt = updatedAt; this.revision = revision;
        }
    }

    public static final class VisibilityRecord {
        public final String metricId, updatedAt;
        public final boolean hidden, dashboardEnabled;
        public final long revision;

        private VisibilityRecord(String metricId, boolean hidden, boolean dashboardEnabled, long revision, String updatedAt) {
            this.metricId = metricId; this.hidden = hidden; this.dashboardEnabled = dashboardEnabled;
            this.revision = revision; this.updatedAt = updatedAt;
        }
    }

    public static final class LayoutRecord {
        public final String id, viewKey, breakpoint, updatedAt;
        public final long revision;
        public final List<LayoutItemRecord> items;

        private LayoutRecord(String id, String viewKey, String breakpoint, long revision, String updatedAt) {
            this(id, viewKey, breakpoint, revision, updatedAt, Collections.emptyList());
        }

        private LayoutRecord(String id, String viewKey, String breakpoint, long revision, String updatedAt,
                List<LayoutItemRecord> items) {
            this.id = id; this.viewKey = viewKey; this.breakpoint = breakpoint; this.revision = revision;
            this.updatedAt = updatedAt; this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }

        private LayoutRecord withItems(List<LayoutItemRecord> values) {
            return new LayoutRecord(id, viewKey, breakpoint, revision, updatedAt, values);
        }
    }

    public static final class LayoutItemRecord {
        public final String widgetId;
        public final int x, y, w, h, minW, minH, maxW, maxH;

        public LayoutItemRecord(String widgetId, int x, int y, int w, int h, int minW, int minH, int maxW, int maxH) {
            this.widgetId = widgetId; this.x = x; this.y = y; this.w = w; this.h = h;
            this.minW = minW; this.minH = minH; this.maxW = maxW; this.maxH = maxH;
        }
    }

    public static final class DependencyRecord {
        public final String kind, key, metricId, categoryId, accountId;

        public DependencyRecord(String kind, String key, String metricId, String categoryId, String accountId) {
            this.kind = kind; this.key = key; this.metricId = metricId; this.categoryId = categoryId; this.accountId = accountId;
        }
    }

    public static final class FormulaVersionRecord {
        public final String id, formulaId, astJson, tokensJson, createdAt;
        public final int version;

        private FormulaVersionRecord(String id, String formulaId, int version, String astJson,
                String tokensJson, String createdAt) {
            this.id = id; this.formulaId = formulaId; this.version = version; this.astJson = astJson;
            this.tokensJson = tokensJson; this.createdAt = createdAt;
        }
    }

    public static final class MetricFactsRecord {
        public final ResolvedPeriod period;
        public final SystemMetricFacts facts;
        public final Map<String, BigDecimal> categoryIncome, categoryExpense, accountBalances;
        public final long dataRevision;

        private MetricFactsRecord(ResolvedPeriod period, SystemMetricFacts facts, Map<String, BigDecimal> categoryIncome,
                Map<String, BigDecimal> categoryExpense, Map<String, BigDecimal> accountBalances, long dataRevision) {
            this.period = period; this.facts = facts;
            this.categoryIncome = immutableFacts(categoryIncome);
            this.categoryExpense = immutableFacts(categoryExpense);
            this.accountBalances = immutableFacts(accountBalances);
            this.dataRevision = dataRevision;
        }
    }

    private static Map<String, BigDecimal> immutableFacts(Map<String, BigDecimal> values) {
        return Collections.unmodifiableMap(new HashMap<>(values == null ? Collections.emptyMap() : values));
    }

    private static final class CategoryFacts {
        private final Map<String, BigDecimal> income;
        private final Map<String, BigDecimal> expense;

        private CategoryFacts(Map<String, BigDecimal> income, Map<String, BigDecimal> expense) {
            this.income = income;
            this.expense = expense;
        }
    }
}
