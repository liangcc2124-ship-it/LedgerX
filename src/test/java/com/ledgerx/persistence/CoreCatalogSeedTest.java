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
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoreCatalogSeedTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC);
    private static final String V001 = "/db/ledger/migration/V001__bootstrap.sql";
    private static final String V002 = "/db/ledger/migration/V002__ledger_settings.sql";
    private static final String V003 = "/db/ledger/migration/V003__ledger_core_facts.sql";

    @Test
    void freshSeedHasContractCountsAndReopenDoesNotDuplicate(@TempDir Path temp) throws Exception {
        BootstrapSnapshot first = new ProfileBootstrap(temp, CLOCK).open();
        Path ledger = temp.resolve("Profiles").resolve(first.getProfileId()).resolve("ledger.db");
        assertEquals(4, first.getSchemaVersion());
        assertSeedCounts(ledger);
        String openingOn;
        String accountCreatedAt;
        try (Connection connection = new SqliteDatabase(ledger).open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT opening_on, created_at, updated_at, revision, is_system, "
                                + "include_in_available_cash, opening_balance_minor, kind, balance_side "
                                + "FROM financial_account")) {
            result.next();
            openingOn = result.getString("opening_on");
            accountCreatedAt = result.getString("created_at");
            assertEquals(accountCreatedAt, result.getString("updated_at"));
            assertEquals(0, result.getInt("revision"));
            assertEquals(1, result.getInt("is_system"));
            assertEquals(1, result.getInt("include_in_available_cash"));
            assertEquals(0, result.getLong("opening_balance_minor"));
            assertEquals("CASH", result.getString("kind"));
            assertEquals("ASSET", result.getString("balance_side"));
            assertEquals(0, countRows(connection, "processed_operation"));
            assertEquals(0, scalarInt(connection, "SELECT data_revision FROM ledger_meta WHERE id = 1"));
            assertEquals(71, scalarInt(connection, "SELECT COUNT(*) FROM category WHERE is_system = 1"));
        }
        assertEquals(first.getProfileId(), new ProfileBootstrap(temp, CLOCK).open().getProfileId());
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals(71, countRows(connection, "category"));
            assertEquals(65, countRows(connection, "category_record_type"));
            assertEquals(1, countRows(connection, "financial_account"));
            assertEquals(openingOn, scalarString(connection,
                    "SELECT opening_on FROM financial_account WHERE id = 'f3a0c2ec-6b64-48c7-9f7f-0b604e4d8901'"));
            assertEquals(accountCreatedAt, scalarString(connection,
                    "SELECT created_at FROM financial_account WHERE id = 'f3a0c2ec-6b64-48c7-9f7f-0b604e4d8901'"));
            SqliteHealthCheck.full(connection);
        }
    }

    @Test
    void v003UpgradePreservesMetadataAndConflictRollsBack(@TempDir Path temp) throws Exception {
        String profileId = UUID.randomUUID().toString();
        Path ledger = temp.resolve("Profiles").resolve(profileId).resolve("ledger.db");
        Files.createDirectories(ledger.getParent());
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals(3, new MigrationRunner(Arrays.asList(V001, V002, V003), "schema_history", CLOCK)
                    .migrate(connection));
            try (PreparedStatement meta = connection.prepareStatement(
                    "INSERT INTO ledger_meta(id, profile_id, created_at, updated_at, data_revision) "
                            + "VALUES (1, ?, '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z', 7)")) {
                meta.setString(1, profileId);
                meta.executeUpdate();
            }
            try (PreparedStatement category = connection.prepareStatement(
                    "INSERT INTO category(id, name, created_at, updated_at) VALUES (?, '冲突', ?, ?)")) {
                category.setString(1, "4d320f86-3c27-811f-226a-d0443e164cc0");
                category.setString(2, "2026-09-01T00:00:00Z");
                category.setString(3, "2026-09-01T00:00:00Z");
                category.executeUpdate();
            }
        }

        assertThrows(PersistenceException.class, () -> new LedgerBootstrap(CLOCK).open(ledger, profileId));
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals(3, countRows(connection, "schema_history"));
            assertEquals(1, countRows(connection, "category"));
            assertEquals(0, countRows(connection, "category_record_type"));
            assertEquals(0, countRows(connection, "financial_account"));
            assertEquals(7, scalarInt(connection, "SELECT data_revision FROM ledger_meta WHERE id = 1"));
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM category WHERE id = '4d320f86-3c27-811f-226a-d0443e164cc0'");
            }
        }

        assertEquals(4, new LedgerBootstrap(CLOCK).open(ledger, profileId).getSchemaVersion());
        assertSeedCounts(ledger);
    }

    private static void assertSeedCounts(Path ledger) throws Exception {
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals(4, countRows(connection, "schema_history"));
            assertEquals(71, countRows(connection, "category"));
            assertEquals(65, countRows(connection, "category_record_type"));
            assertEquals(1, countRows(connection, "financial_account"));
            assertEquals(0, countRows(connection, "finance_record"));
            SqliteHealthCheck.full(connection);
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

    private static String scalarString(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }
}
