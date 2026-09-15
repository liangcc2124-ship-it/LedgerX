package com.ledgerx.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationRunnerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T02:00:00Z"), ZoneOffset.UTC);
    private static final String V001 = "/db/testmigration/V001__first.sql";
    private static final String V002 = "/db/testmigration/V002__second.sql";

    @Test
    void appliesOrderedResourcesAndReopenIsIdempotent(@TempDir Path temp) throws Exception {
        Path database = temp.resolve("ordered.db");
        MigrationRunner runner = new MigrationRunner(Arrays.asList(V001, V002), "test_schema_history", CLOCK);

        try (Connection connection = new SqliteDatabase(database).open()) {
            assertEquals(2, runner.migrate(connection));
            assertEquals(2, historyCount(connection));
            assertTrue(columnExists(connection, "migration_probe", "second_value"));
        }

        try (Connection connection = new SqliteDatabase(database).open()) {
            String installed = installedAt(connection, 2);
            assertEquals(2, runner.migrate(connection));
            assertEquals(2, historyCount(connection));
            assertEquals(installed, installedAt(connection, 2));
        }
    }

    @Test
    void existingV001UpgradesWithoutChangingRows(@TempDir Path temp) throws Exception {
        Path database = temp.resolve("upgrade.db");
        try (Connection connection = new SqliteDatabase(database).open()) {
            new MigrationRunner(V001, "test_schema_history", CLOCK).migrate(connection);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO migration_probe(id, value) VALUES (1, 'keep')")) {
                insert.executeUpdate();
            }
        }

        try (Connection connection = new SqliteDatabase(database).open()) {
            assertEquals(2, new MigrationRunner(Arrays.asList(V001, V002),
                    "test_schema_history", CLOCK).migrate(connection));
            assertEquals("keep", scalarString(connection, "SELECT value FROM migration_probe WHERE id = 1"));
            assertEquals(2, historyCount(connection));
        }
    }

    @Test
    void invalidResourceSetsAreRejectedBeforeSchemaChanges(@TempDir Path temp) throws Exception {
        Path database = temp.resolve("invalid.db");
        try (Connection connection = new SqliteDatabase(database).open()) {
            assertThrows(PersistenceException.class, () -> new MigrationRunner(
                    Arrays.asList(V001, V001), "test_schema_history", CLOCK).migrate(connection));
            assertThrows(PersistenceException.class, () -> new MigrationRunner(
                    Arrays.asList(V001, "/db/testmigration/V003__third.sql"),
                    "test_schema_history", CLOCK).migrate(connection));
            assertThrows(PersistenceException.class, () -> new MigrationRunner(
                    "/db/testmigration/not-versioned.sql", "test_schema_history", CLOCK).migrate(connection));
            assertFalse(tableExists(connection, "test_schema_history"));
            assertFalse(tableExists(connection, "migration_probe"));
        }
    }

    @Test
    void failedLaterMigrationRollsBackItsSchemaAndHistory(@TempDir Path temp) throws Exception {
        Path database = temp.resolve("rollback.db");
        try (Connection connection = new SqliteDatabase(database).open()) {
            new MigrationRunner(V001, "test_schema_history", CLOCK).migrate(connection);
            assertThrows(PersistenceException.class, () -> new MigrationRunner(
                    Arrays.asList(V001, "/db/testmigration/V002__broken.sql"),
                    "test_schema_history", CLOCK).migrate(connection));
            assertEquals(1, historyCount(connection));
            assertFalse(columnExists(connection, "migration_probe", "broken_value"));
        }
    }

    @Test
    void badChecksumIncompleteAndFutureHistoryAreRejected(@TempDir Path temp) throws Exception {
        Path database = temp.resolve("history.db");
        MigrationRunner runner = new MigrationRunner(Arrays.asList(V001, V002), "test_schema_history", CLOCK);
        try (Connection connection = new SqliteDatabase(database).open()) {
            runner.migrate(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE test_schema_history SET checksum = 'bad' WHERE version = 2");
            }
            assertThrows(PersistenceException.class, () -> runner.migrate(connection));

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM test_schema_history WHERE version = 2");
                statement.executeUpdate("UPDATE test_schema_history SET success = 0 WHERE version = 1");
            }
            assertThrows(PersistenceException.class, () -> runner.migrate(connection));

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM test_schema_history");
                statement.executeUpdate("INSERT INTO test_schema_history "
                        + "(version, description, checksum, installed_at, success) "
                        + "VALUES (3, 'future', 'future', '2026-09-13T02:00:00Z', 1)");
            }
            assertThrows(PersistenceException.class, () -> runner.migrate(connection));
        }
    }

    private static int historyCount(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM test_schema_history")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String installedAt(Connection connection, int version) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT installed_at FROM test_schema_history WHERE version = ?")) {
            statement.setInt(1, version);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static String scalarString(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static boolean tableExists(Connection connection, String name) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equals(result.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }
}
