package com.ledgerx.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsSchemaV005Test {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC);
    private static final String V001 = "/db/ledger/migration/V001__bootstrap.sql";
    private static final String V002 = "/db/ledger/migration/V002__ledger_settings.sql";
    private static final String V003 = "/db/ledger/migration/V003__ledger_core_facts.sql";
    private static final String V004 = "/db/ledger/migration/V004__core_catalog_seed.sql";

    @Test
    void freshLedgerHasMetricDefinitionsVisibilityAndDefaultLayout(@TempDir Path temp) throws Exception {
        BootstrapSnapshot snapshot = new ProfileBootstrap(temp, CLOCK).open();
        Path ledger = temp.resolve("Profiles").resolve(snapshot.getProfileId()).resolve("ledger.db");

        assertEquals(6, snapshot.getSchemaVersion());
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals(17, countRows(connection, "metric_definition"));
            assertEquals(17, countRows(connection, "metric_visibility"));
            assertEquals(1, countRows(connection, "dashboard_layout"));
            assertEquals(8, countRows(connection, "dashboard_layout_item"));
            assertEquals(8, scalarInt(connection,
                    "SELECT COUNT(*) FROM metric_visibility WHERE dashboard_enabled = 1"));
            assertEquals(0, countRows(connection, "formula_definition"));
            assertEquals(0, scalarInt(connection,
                    "SELECT COUNT(*) FROM metric_definition WHERE is_system <> 1 OR current_formula_version_id IS NOT NULL"));
            assertEquals(0, scalarInt(connection, "SELECT data_revision FROM ledger_meta WHERE id = 1"));
            assertEquals(1, scalarInt(connection,
                    "SELECT COUNT(*) FROM dashboard_layout WHERE id = '00000000-0000-0000-0000-000000000005'"));
            assertEquals(8, scalarInt(connection,
                    "SELECT COUNT(*) FROM dashboard_layout_item WHERE min_w = 3 AND min_h = 2 AND max_w = 12 AND max_h = 6"));
            assertEquals(1, scalarInt(connection,
                    "SELECT COUNT(*) FROM metric_definition WHERE id = 'income' AND name = '收入'"));
            assertEquals(1, scalarInt(connection,
                    "SELECT COUNT(*) FROM metric_definition WHERE id = 'transaction-count' AND display_format = 'INTEGER' AND precision = 0"));
        }

        BootstrapSnapshot reopened = new ProfileBootstrap(temp, CLOCK).open();
        assertEquals(6, reopened.getSchemaVersion());
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals(17, countRows(connection, "metric_definition"));
            assertEquals(8, countRows(connection, "dashboard_layout_item"));
        }
    }

    @Test
    void v004UpgradePreservesRecordsAndDataRevision(@TempDir Path temp) throws Exception {
        String profileId = UUID.randomUUID().toString();
        Path ledger = temp.resolve("Profiles").resolve(profileId).resolve("ledger.db");
        Files.createDirectories(ledger.getParent());
        migrateToV004AndInsertRecord(ledger, profileId, false);

        BootstrapSnapshot upgraded = new LedgerBootstrap(CLOCK).open(ledger, profileId);
        assertEquals(6, upgraded.getSchemaVersion());
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals(6, countRows(connection, "schema_history"));
            assertEquals(1, countRows(connection, "finance_record"));
            assertEquals(7, scalarInt(connection, "SELECT data_revision FROM ledger_meta WHERE id = 1"));
            assertEquals(17, countRows(connection, "metric_definition"));
        }
    }

    @Test
    void failedV005RollsBackAllMetricObjects(@TempDir Path temp) throws Exception {
        String profileId = UUID.randomUUID().toString();
        Path ledger = temp.resolve("Profiles").resolve(profileId).resolve("ledger.db");
        Files.createDirectories(ledger.getParent());
        migrateToV004AndInsertRecord(ledger, profileId, true);

        assertThrows(PersistenceException.class, () -> new LedgerBootstrap(CLOCK).open(ledger, profileId));
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals(4, countRows(connection, "schema_history"));
            assertTrue(tableExists(connection, "formula_definition"));
            assertFalse(tableExists(connection, "metric_definition"));
            assertFalse(tableExists(connection, "metric_visibility"));
            assertEquals(1, countRows(connection, "finance_record"));
            assertEquals(7, scalarInt(connection, "SELECT data_revision FROM ledger_meta WHERE id = 1"));
        }
    }

    private static void migrateToV004AndInsertRecord(Path ledger, String profileId, boolean createConflict)
            throws Exception {
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals(4, new MigrationRunner(Arrays.asList(V001, V002, V003, V004), "schema_history", CLOCK)
                    .migrate(connection));
            try (PreparedStatement meta = connection.prepareStatement(
                    "INSERT INTO ledger_meta(id, profile_id, created_at, updated_at, data_revision) "
                            + "VALUES (1, ?, '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z', 7)")) {
                meta.setString(1, profileId);
                meta.executeUpdate();
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO finance_record "
                                + "(id, occurred_on, record_type, amount_minor, currency_code, category_id, account_id, "
                                + "settlement_mode, settlement_on, note, created_at, updated_at) "
                                + "SELECT '6d8e3c2e-4a1c-4e3a-9c7c-8b2c47a5f501', '2026-09-01', 'INCOME', 100, 'CNY', "
                                + "c.id, a.id, 'PAID_FROM_ACCOUNT', '2026-09-01', 'fixture', "
                                + "'2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z' "
                                + "FROM category c, financial_account a WHERE c.is_system = 1 AND a.is_system = 1 LIMIT 1");
                if (createConflict) {
                    statement.executeUpdate("CREATE TABLE formula_definition (id TEXT PRIMARY KEY NOT NULL)");
                }
            }
        }
    }

    private static int countRows(Connection connection, String table) throws Exception {
        return scalarInt(connection, "SELECT COUNT(*) FROM " + table);
    }

    private static int scalarInt(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }
}
