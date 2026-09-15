package com.ledgerx.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerV003SchemaTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC);
    private static final String V001 = "/db/ledger/migration/V001__bootstrap.sql";
    private static final String V002 = "/db/ledger/migration/V002__ledger_settings.sql";
    private static final String V003_BROKEN = "/db/testmigration/V003__broken.sql";

    @Test
    void freshProfileAppliesV003AndReopenIsIdempotent(@TempDir Path temp) throws Exception {
        BootstrapSnapshot first = new ProfileBootstrap(temp, CLOCK).open();
        Path ledger = temp.resolve("Profiles").resolve(first.getProfileId()).resolve("ledger.db");

        assertEquals(4, first.getSchemaVersion());
        assertEquals(4, countRows(ledger, "schema_history"));
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertForeignKeysEnabled(connection);
            assertEquals(Arrays.asList("category", "category_record_type", "finance_record",
                            "financial_account", "ledger_meta", "ledger_setting", "processed_operation",
                            "schema_history"), tableNames(connection));
            assertEquals(Arrays.asList("idx_category_parent_archived_sort",
                            "idx_category_record_type_record_type_category",
                            "idx_finance_record_account_deleted_occurred",
                            "idx_finance_record_category_deleted_occurred",
                            "idx_finance_record_deleted_occurred_id",
                            "idx_finance_record_settlement_deleted",
                            "idx_finance_record_type_deleted_occurred",
                            "idx_financial_account_archived_name",
                            "idx_processed_operation_expires_at",
                            "ux_category_active_parent_name",
                            "ux_category_active_top_name",
                            "ux_financial_account_active_name"), indexNames(connection));
            assertEquals(71, countRows(connection, "category"));
            assertEquals(65, countRows(connection, "category_record_type"));
            assertEquals(1, countRows(connection, "financial_account"));
            assertEquals(0, countRows(connection, "finance_record"));
            assertColumns(connection, "category", "id", "parent_id", "name", "is_system",
                    "is_legacy_custom", "archived_at", "sort_order", "default_recognition_method",
                    "recommended_depreciation_method", "created_at", "updated_at", "revision");
            assertColumns(connection, "category_record_type", "category_id", "record_type");
            assertColumns(connection, "financial_account", "id", "name", "kind", "balance_side",
                    "opening_on", "opening_balance_minor", "include_in_available_cash", "is_system",
                    "archived_at", "created_at", "updated_at", "revision");
            assertColumns(connection, "finance_record", "id", "occurred_on", "record_type", "amount_minor",
                    "currency_code", "category_id", "account_id", "settlement_mode", "settlement_on",
                    "income_source", "is_self_generated_income", "is_non_essential", "note", "created_at",
                    "updated_at", "deleted_at", "revision");
            assertNotNullColumns(connection, "category", "id", "name", "is_system", "is_legacy_custom",
                    "sort_order", "default_recognition_method", "created_at", "updated_at", "revision");
            assertNotNullColumns(connection, "category_record_type", "category_id", "record_type");
            assertNotNullColumns(connection, "financial_account", "id", "name", "kind", "balance_side",
                    "opening_on", "opening_balance_minor", "include_in_available_cash", "is_system",
                    "created_at", "updated_at", "revision");
            assertNotNullColumns(connection, "finance_record", "id", "occurred_on", "record_type", "amount_minor",
                    "currency_code", "settlement_mode", "is_self_generated_income", "is_non_essential", "note",
                    "created_at", "updated_at", "revision");
            assertPartialIndexSql(connection, "ux_category_active_top_name", "parent_id IS NULL", "archived_at IS NULL");
            assertPartialIndexSql(connection, "ux_category_active_parent_name", "parent_id IS NOT NULL", "archived_at IS NULL");
            assertPartialIndexSql(connection, "ux_financial_account_active_name", "archived_at IS NULL");
            SqliteHealthCheck.full(connection);
            String installedAt = scalarString(connection,
                    "SELECT installed_at FROM schema_history WHERE version = 3");
            assertNotNull(installedAt);

            BootstrapSnapshot reopened = new ProfileBootstrap(temp, CLOCK).open();
            assertEquals(first.getProfileId(), reopened.getProfileId());
            assertEquals(4, reopened.getSchemaVersion());
            try (Connection reopenedConnection = new SqliteDatabase(ledger).open()) {
                assertEquals(4, countRows(reopenedConnection, "schema_history"));
                assertEquals(installedAt, scalarString(reopenedConnection,
                        "SELECT installed_at FROM schema_history WHERE version = 3"));
                SqliteHealthCheck.full(reopenedConnection);
            }
        }
    }

    @Test
    void existingV002UpgradePreservesSettingsMetadataAndOperations(@TempDir Path temp) throws Exception {
        String profileId = UUID.randomUUID().toString();
        Path ledger = temp.resolve("Profiles").resolve(profileId).resolve("ledger.db");
        java.nio.file.Files.createDirectories(ledger.getParent());
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals(2, new MigrationRunner(Arrays.asList(V001, V002), "schema_history", CLOCK)
                    .migrate(connection));
            try (PreparedStatement meta = connection.prepareStatement(
                    "INSERT INTO ledger_meta (id, profile_id, created_at, updated_at, data_revision) "
                            + "VALUES (1, ?, '2026-09-01T00:00:00Z', '2026-09-02T00:00:00Z', 4)")) {
                meta.setString(1, profileId);
                meta.executeUpdate();
            }
            try (PreparedStatement settings = connection.prepareStatement(
                    "UPDATE ledger_setting SET notifications_enabled = 1, auto_backup_interval_days = 31, "
                            + "safety_buffer_minor = 12345, revision = 6, updated_at = '2026-09-02T00:00:00Z' "
                            + "WHERE id = 1")) {
                assertEquals(1, settings.executeUpdate());
            }
            try (PreparedStatement operation = connection.prepareStatement(
                    "INSERT INTO processed_operation (idempotency_key, http_method, canonical_path, request_hash, "
                            + "response_status, response_json, profile_id, completed_at, expires_at) "
                            + "VALUES (?, 'PATCH', '/api/v1/settings', ?, 200, '{}', ?, "
                            + "'2026-09-02T00:00:00Z', '2026-09-09T00:00:00Z')")) {
                operation.setString(1, UUID.randomUUID().toString());
                operation.setString(2, "a".repeat(64));
                operation.setString(3, profileId);
                operation.executeUpdate();
            }
        }

        BootstrapSnapshot upgraded = new LedgerBootstrap(CLOCK).open(ledger, profileId);
        assertEquals(4, upgraded.getSchemaVersion());
        assertEquals(4, upgraded.getDataRevision());
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals(4, countRows(connection, "schema_history"));
            assertEquals(1, countRows(connection, "processed_operation"));
            try (PreparedStatement meta = connection.prepareStatement(
                    "SELECT profile_id, updated_at, data_revision FROM ledger_meta WHERE id = 1");
                    ResultSet result = meta.executeQuery()) {
                assertTrue(result.next());
                assertEquals(profileId, result.getString("profile_id"));
                assertEquals("2026-09-02T00:00:00Z", result.getString("updated_at"));
                assertEquals(4, result.getLong("data_revision"));
            }
            try (PreparedStatement settings = connection.prepareStatement(
                    "SELECT notifications_enabled, auto_backup_interval_days, safety_buffer_minor, revision "
                            + "FROM ledger_setting WHERE id = 1");
                    ResultSet result = settings.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt("notifications_enabled"));
                assertEquals(31, result.getInt("auto_backup_interval_days"));
                assertEquals(12345, result.getLong("safety_buffer_minor"));
                assertEquals(6, result.getLong("revision"));
            }
            assertEquals(71, countRows(connection, "category"));
            assertEquals(1, countRows(connection, "financial_account"));
            assertEquals(0, countRows(connection, "finance_record"));
            SqliteHealthCheck.full(connection);
        }
    }

    @Test
    void v003ConstraintsDefaultsAndIndexesRejectInvalidFacts(@TempDir Path temp) throws Exception {
        Path ledger = createFreshLedger(temp);
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            insertCategory(connection, "category-root", null, "Food", null);
            assertThrows(SQLException.class,
                    () -> insertCategory(connection, "category-duplicate", null, "food", null));
            insertCategory(connection, "category-archived", null, "FOOD", "2026-09-03T00:00:00Z");
            insertCategory(connection, "category-child", "category-root", "Dining", null);
            assertThrows(SQLException.class,
                    () -> insertCategory(connection, "category-child-duplicate", "category-root", "dining", null));
            insertCategory(connection, "category-other-child", "category-archived", "Dining", null);
            assertThrows(SQLException.class,
                    () -> insertCategory(connection, "category-bad-parent", "missing", "Bad", null));

            insertCategoryRecordType(connection, "category-root", "INCOME");
            assertThrows(SQLException.class,
                    () -> insertCategoryRecordType(connection, "category-root", "INCOME"));
            assertThrows(SQLException.class,
                    () -> insertCategoryRecordType(connection, "category-root", "UNKNOWN"));

            insertAccount(connection, "account-cash", "Cash", "CASH", "ASSET", 0, null);
            assertThrows(SQLException.class,
                    () -> insertAccount(connection, "account-duplicate", "cash", "CASH", "ASSET", 0, null));
            insertAccount(connection, "account-archived", "CASH", "CASH", "ASSET", 0,
                    "2026-09-03T00:00:00Z");
            assertThrows(SQLException.class,
                    () -> insertAccount(connection, "account-liability-cash", "Loan", "LOAN", "LIABILITY", 1, null));
            assertThrows(SQLException.class,
                    () -> insertAccount(connection, "account-bad-kind", "Bad", "UNKNOWN", "ASSET", 0, null));
            assertThrows(SQLException.class,
                    () -> insertAccount(connection, "account-bad-side", "Bad", "CASH", "UNKNOWN", 0, null));

            insertRecord(connection, "record-valid", "category-root", "account-cash", "INCOME", 100);
            assertThrows(SQLException.class,
                    () -> insertRecord(connection, "record-zero", "category-root", "account-cash", "INCOME", 0));
            assertThrows(SQLException.class,
                    () -> insertRecord(connection, "record-type", "category-root", "account-cash", "UNKNOWN", 100));
            assertThrows(SQLException.class,
                    () -> insertRecordWithCurrency(connection, "record-currency", "category-root", "account-cash", "USD"));
            assertThrows(SQLException.class,
                    () -> insertRecordWithNote(connection, "record-note", "category-root", "account-cash", "x".repeat(4001)));
            assertThrows(SQLException.class,
                    () -> insertRecord(connection, "record-category-fk", "missing", "account-cash", "INCOME", 100));
            assertThrows(SQLException.class,
                    () -> insertRecord(connection, "record-account-fk", "category-root", "missing", "INCOME", 100));

            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT is_system, is_legacy_custom, sort_order, default_recognition_method, revision "
                            + "FROM category WHERE id = 'category-root'");
                    ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0, result.getInt("is_system"));
                assertEquals(0, result.getInt("is_legacy_custom"));
                assertEquals(0, result.getInt("sort_order"));
                assertEquals("IMMEDIATE", result.getString("default_recognition_method"));
                assertEquals(0, result.getInt("revision"));
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT include_in_available_cash, is_system, revision FROM financial_account "
                            + "WHERE id = 'account-cash'");
                    ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0, result.getInt("include_in_available_cash"));
                assertEquals(0, result.getInt("is_system"));
                assertEquals(0, result.getInt("revision"));
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT currency_code, is_self_generated_income, is_non_essential, note, revision "
                            + "FROM finance_record WHERE id = 'record-valid'");
                    ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals("CNY", result.getString("currency_code"));
                assertEquals(0, result.getInt("is_self_generated_income"));
                assertEquals(0, result.getInt("is_non_essential"));
                assertEquals("", result.getString("note"));
                assertEquals(0, result.getInt("revision"));
            }

            assertThrows(SQLException.class, () -> execute(connection,
                    "DELETE FROM category WHERE id = 'category-root'"));
            assertThrows(SQLException.class, () -> execute(connection,
                    "DELETE FROM financial_account WHERE id = 'account-cash'"));
            SqliteHealthCheck.full(connection);
        }
    }

    @Test
    void failedV003RollsBackAndV002LedgerCanReopen(@TempDir Path temp) throws Exception {
        String profileId = UUID.randomUUID().toString();
        Path ledger = temp.resolve("Profiles").resolve(profileId).resolve("ledger.db");
        java.nio.file.Files.createDirectories(ledger.getParent());
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals(2, new MigrationRunner(Arrays.asList(V001, V002), "schema_history", CLOCK)
                    .migrate(connection));
            try (PreparedStatement meta = connection.prepareStatement(
                    "INSERT INTO ledger_meta (id, profile_id, created_at, updated_at, data_revision) "
                            + "VALUES (1, ?, '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z', 0)")) {
                meta.setString(1, profileId);
                meta.executeUpdate();
            }
            assertThrows(PersistenceException.class, () -> new MigrationRunner(
                    Arrays.asList(V001, V002, V003_BROKEN), "schema_history", CLOCK).migrate(connection));
            assertEquals(2, countRows(connection, "schema_history"));
            assertFalse(tableExists(connection, "migration_probe_v003"));
            assertFalse(tableExists(connection, "category"));
            assertEquals(profileId, scalarString(connection,
                    "SELECT profile_id FROM ledger_meta WHERE id = 1"));
        }

        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals(2, new MigrationRunner(Arrays.asList(V001, V002), "schema_history", CLOCK)
                    .migrate(connection));
        }
        BootstrapSnapshot reopened = new LedgerBootstrap(CLOCK).open(ledger, profileId);
        assertEquals(4, reopened.getSchemaVersion());
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals(4, countRows(connection, "schema_history"));
            assertTrue(tableExists(connection, "category"));
            SqliteHealthCheck.full(connection);
        }
    }

    private static Path createFreshLedger(Path temp) throws Exception {
        BootstrapSnapshot snapshot = new ProfileBootstrap(temp, CLOCK).open();
        return temp.resolve("Profiles").resolve(snapshot.getProfileId()).resolve("ledger.db");
    }

    private static void insertCategory(Connection connection, String id, String parentId,
            String name, String archivedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO category(id, parent_id, name, archived_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '2026-09-14T00:00:00Z', '2026-09-14T00:00:00Z')")) {
            statement.setString(1, id);
            if (parentId == null) {
                statement.setNull(2, java.sql.Types.VARCHAR);
            } else {
                statement.setString(2, parentId);
            }
            statement.setString(3, name);
            if (archivedAt == null) {
                statement.setNull(4, java.sql.Types.VARCHAR);
            } else {
                statement.setString(4, archivedAt);
            }
            statement.executeUpdate();
        }
    }

    private static void insertCategoryRecordType(Connection connection, String categoryId,
            String recordType) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO category_record_type(category_id, record_type) VALUES (?, ?)")) {
            statement.setString(1, categoryId);
            statement.setString(2, recordType);
            statement.executeUpdate();
        }
    }

    private static void insertAccount(Connection connection, String id, String name, String kind,
            String balanceSide, int includeInAvailableCash, String archivedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO financial_account(id, name, kind, balance_side, opening_on, opening_balance_minor, "
                        + "include_in_available_cash, archived_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '2026-09-14', 0, ?, ?, '2026-09-14T00:00:00Z', "
                        + "'2026-09-14T00:00:00Z')")) {
            statement.setString(1, id);
            statement.setString(2, name);
            statement.setString(3, kind);
            statement.setString(4, balanceSide);
            statement.setInt(5, includeInAvailableCash);
            if (archivedAt == null) {
                statement.setNull(6, java.sql.Types.VARCHAR);
            } else {
                statement.setString(6, archivedAt);
            }
            statement.executeUpdate();
        }
    }

    private static void insertRecord(Connection connection, String id, String categoryId,
            String accountId, String recordType, long amountMinor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO finance_record(id, occurred_on, record_type, amount_minor, category_id, account_id, "
                        + "settlement_mode, created_at, updated_at) VALUES (?, '2026-09-14', ?, ?, ?, ?, "
                        + "'NON_CASH', '2026-09-14T00:00:00Z', '2026-09-14T00:00:00Z')")) {
            statement.setString(1, id);
            statement.setString(2, recordType);
            statement.setLong(3, amountMinor);
            statement.setString(4, categoryId);
            statement.setString(5, accountId);
            statement.executeUpdate();
        }
    }

    private static void insertRecordWithCurrency(Connection connection, String id, String categoryId,
            String accountId, String currency) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO finance_record(id, occurred_on, record_type, amount_minor, currency_code, "
                        + "category_id, account_id, settlement_mode, created_at, updated_at) VALUES (?, "
                        + "'2026-09-14', 'INCOME', 100, ?, ?, ?, 'NON_CASH', '2026-09-14T00:00:00Z', "
                        + "'2026-09-14T00:00:00Z')")) {
            statement.setString(1, id);
            statement.setString(2, currency);
            statement.setString(3, categoryId);
            statement.setString(4, accountId);
            statement.executeUpdate();
        }
    }

    private static void insertRecordWithNote(Connection connection, String id, String categoryId,
            String accountId, String note) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO finance_record(id, occurred_on, record_type, amount_minor, category_id, account_id, "
                        + "settlement_mode, note, created_at, updated_at) VALUES (?, '2026-09-14', 'INCOME', 100, "
                        + "?, ?, 'NON_CASH', ?, '2026-09-14T00:00:00Z', '2026-09-14T00:00:00Z')")) {
            statement.setString(1, id);
            statement.setString(2, categoryId);
            statement.setString(3, accountId);
            statement.setString(4, note);
            statement.executeUpdate();
        }
    }

    private static void assertForeignKeysEnabled(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }

    private static void assertColumns(Connection connection, String table, String... expected) throws SQLException {
        assertEquals(Arrays.asList(expected), columns(connection, table));
    }

    private static List<String> columns(Connection connection, String table) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                result.add(rows.getString("name"));
            }
        }
        return result;
    }

    private static void assertNotNullColumns(Connection connection, String table, String... expected)
            throws SQLException {
        Set<String> required = new HashSet<>(Arrays.asList(expected));
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                String name = rows.getString("name");
                if (rows.getInt("notnull") == 1) {
                    assertTrue(required.remove(name), () -> table + " unexpectedly requires " + name);
                }
            }
        }
        assertTrue(required.isEmpty(), () -> table + " missing NOT NULL columns " + required);
    }

    private static void assertPartialIndexSql(Connection connection, String indexName, String... fragments)
            throws SQLException {
        String sql = scalarString(connection, "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = '"
                + indexName + "'");
        assertNotNull(sql);
        String normalized = sql.toUpperCase(Locale.ROOT);
        for (String fragment : fragments) {
            assertTrue(normalized.contains(fragment.toUpperCase(Locale.ROOT)),
                    () -> indexName + " missing condition " + fragment);
        }
    }

    private static int countRows(Path database, String table) throws Exception {
        try (Connection connection = new SqliteDatabase(database).open()) {
            return countRows(connection, table);
        }
    }

    private static int countRows(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static String scalarString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static List<String> tableNames(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")) {
            while (result.next()) {
                names.add(result.getString(1));
            }
        }
        return names;
    }

    private static List<String> indexNames(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'index' "
                                + "AND name NOT LIKE 'sqlite_autoindex_%' ORDER BY name")) {
            while (result.next()) {
                names.add(result.getString(1));
            }
        }
        return names;
    }
}
