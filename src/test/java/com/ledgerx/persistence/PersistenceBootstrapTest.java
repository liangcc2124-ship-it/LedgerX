package com.ledgerx.persistence;

import com.ledgerx.application.bootstrap.ApplicationBootstrap;
import com.ledgerx.application.system.SystemStatus;
import com.ledgerx.application.system.SystemStatusProvider;
import com.ledgerx.http.BuildMetadata;
import com.ledgerx.http.LedgerHttpServer;
import com.ledgerx.http.StaticResourceManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceBootstrapTest {
    private static final String TOKEN = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-12T08:30:00Z"), ZoneOffset.UTC);

    @Test
    void firstStartCreatesOneProfileAndReopenDoesNotDuplicate(@TempDir Path temp) throws Exception {
        ProfileBootstrap bootstrap = new ProfileBootstrap(temp, CLOCK);
        BootstrapSnapshot first = bootstrap.open();
        Path profileDirectory = temp.resolve("Profiles").resolve(first.getProfileId());
        Path ledger = profileDirectory.resolve("ledger.db");

        assertTrue(Files.isRegularFile(temp.resolve("profiles.db")));
        assertTrue(Files.isDirectory(profileDirectory));
        assertTrue(Files.isRegularFile(ledger));
        assertEquals(4, first.getSchemaVersion());
        assertEquals(0, first.getDataRevision());
        assertEquals(36, first.getProfileId().length());
        assertEquals(first.getProfileId(), first.getProfileId().toLowerCase());

        assertCatalogShape(temp.resolve("profiles.db"), first.getProfileId());
        assertLedgerShape(ledger, first.getProfileId());
        assertCatalogConstraints(temp.resolve("profiles.db"), first.getProfileId());
        assertTrue(Files.notExists(Path.of(ledger + "-wal")));
        assertTrue(Files.notExists(Path.of(ledger + "-shm")));
        try (Connection catalog = new SqliteDatabase(temp.resolve("profiles.db")).open()) {
            SqliteHealthCheck.full(catalog);
        }
        new LedgerBootstrap(CLOCK).fullHealth(ledger);

        BootstrapSnapshot reopened = new ProfileBootstrap(temp, CLOCK).open();
        assertEquals(first.getProfileId(), reopened.getProfileId());
        assertEquals(1, countRows(temp.resolve("profiles.db"), "profile"));
        assertEquals(1, countRows(temp.resolve("profiles.db"), "catalog_setting"));
        assertEquals(4, countRows(ledger, "schema_history"));
        assertEquals(1, countRows(ledger, "ledger_meta"));
        assertEquals(0, reopened.getDataRevision());
    }

    @Test
    void v001CatalogAndLedgerUpgradeToV003WithoutChangingProfileFacts(@TempDir Path temp) throws Exception {
        String profileId = UUID.randomUUID().toString();
        Path profileDirectory = temp.resolve("Profiles").resolve(profileId);
        Path ledger = profileDirectory.resolve("ledger.db");
        Files.createDirectories(profileDirectory);
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            new MigrationRunner("/db/ledger/migration/V001__bootstrap.sql", "schema_history", CLOCK)
                    .migrate(connection);
            try (PreparedStatement meta = connection.prepareStatement(
                    "INSERT INTO ledger_meta (id, profile_id, created_at, updated_at, data_revision) "
                            + "VALUES (1, ?, '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z', 0)")) {
                meta.setString(1, profileId);
                meta.executeUpdate();
            }
        }

        Path catalogFile = temp.resolve("profiles.db");
        try (Connection catalog = new SqliteDatabase(catalogFile).open()) {
            new MigrationRunner("/db/catalog/migration/V001__bootstrap.sql",
                    "catalog_schema_history", CLOCK).migrate(catalog);
            try (PreparedStatement profile = catalog.prepareStatement(
                    "INSERT INTO profile "
                            + "(id, name, relative_directory, created_at, last_opened_at, archived_at, revision) "
                            + "VALUES (?, '旧空间', ?, '2026-09-01T00:00:00Z', '2026-09-02T00:00:00Z', NULL, 7)");
                    PreparedStatement setting = catalog.prepareStatement(
                            "INSERT INTO catalog_setting(id, active_profile_id, updated_at) "
                                    + "VALUES (1, ?, '2026-09-02T00:00:00Z')")) {
                profile.setString(1, profileId);
                profile.setString(2, "Profiles/" + profileId);
                profile.executeUpdate();
                setting.setString(1, profileId);
                setting.executeUpdate();
            }
        }
        try (Connection catalog = new SqliteDatabase(catalogFile).open()) {
            assertEquals(2, new MigrationRunner(Arrays.asList(
                    "/db/catalog/migration/V001__bootstrap.sql",
                    "/db/catalog/migration/V002__catalog_operations.sql"),
                    "catalog_schema_history", CLOCK).migrate(catalog));
        }
        try (Connection catalog = new SqliteDatabase(catalogFile).open();
                PreparedStatement profile = catalog.prepareStatement(
                        "SELECT name, relative_directory, created_at, last_opened_at, archived_at, revision "
                                + "FROM profile WHERE id = ?")) {
            profile.setString(1, profileId);
            try (ResultSet result = profile.executeQuery()) {
                assertTrue(result.next());
                assertEquals("旧空间", result.getString("name"));
                assertEquals("Profiles/" + profileId, result.getString("relative_directory"));
                assertEquals("2026-09-01T00:00:00Z", result.getString("created_at"));
                assertEquals("2026-09-02T00:00:00Z", result.getString("last_opened_at"));
                assertEquals(null, result.getString("archived_at"));
                assertEquals(7, result.getInt("revision"));
            }
            assertEquals(2, countRows(catalog, "catalog_schema_history"));
            assertEquals(0, countRows(catalog, "catalog_processed_operation"));
        }

        BootstrapSnapshot upgraded = new ProfileBootstrap(temp, CLOCK).open();
        assertEquals(profileId, upgraded.getProfileId());
        assertEquals(4, upgraded.getSchemaVersion());
        assertEquals(0, upgraded.getDataRevision());
        try (Connection connection = new SqliteDatabase(ledger).open();
                PreparedStatement setting = connection.prepareStatement(
                        "SELECT notifications_enabled, auto_backup_enabled, safety_buffer_minor, revision "
                                + "FROM ledger_setting WHERE id = 1");
                ResultSet result = setting.executeQuery()) {
            assertTrue(result.next());
            assertEquals(0, result.getInt("notifications_enabled"));
            assertEquals(1, result.getInt("auto_backup_enabled"));
            assertEquals(300000, result.getLong("safety_buffer_minor"));
            assertEquals(0, result.getLong("revision"));
            assertFalse(result.next());
            assertEquals(4, countRows(connection, "schema_history"));
        }
    }

    @Test
    void catalogV002OperationConstraintsAreEnforced(@TempDir Path temp) throws Exception {
        new ProfileBootstrap(temp, CLOCK).open();
        try (Connection connection = new SqliteDatabase(temp.resolve("profiles.db")).open()) {
            String sql = "INSERT INTO catalog_processed_operation "
                    + "(idempotency_key, http_method, canonical_path, request_hash, response_status, "
                    + "response_json, completed_at, expires_at) VALUES (?, ?, ?, ?, ?, '{}', ?, ?)";
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                setOperation(insert, UUID.randomUUID().toString(), "POST", "/api/v1/profiles",
                        "a".repeat(64), 201, "2026-09-13T02:00:00Z", "2026-09-20T02:00:00Z");
                assertEquals(1, insert.executeUpdate());

                assertOperationRejected(insert, UUID.randomUUID().toString(), "PUT", "/api/v1/profiles",
                        "a".repeat(64), 200, "2026-09-13T02:00:00Z", "2026-09-20T02:00:00Z");
                assertOperationRejected(insert, UUID.randomUUID().toString(), "POST", "",
                        "a".repeat(64), 200, "2026-09-13T02:00:00Z", "2026-09-20T02:00:00Z");
                assertOperationRejected(insert, UUID.randomUUID().toString(), "POST", "/api/v1/profiles",
                        "a".repeat(63), 200, "2026-09-13T02:00:00Z", "2026-09-20T02:00:00Z");
                assertOperationRejected(insert, UUID.randomUUID().toString(), "POST", "/api/v1/profiles",
                        "a".repeat(64), 199, "2026-09-13T02:00:00Z", "2026-09-20T02:00:00Z");
                assertOperationRejected(insert, UUID.randomUUID().toString(), "POST", "/api/v1/profiles",
                        "a".repeat(64), 200, "2026-09-13T02:00:00Z", "2026-09-13T02:00:00Z");
            }
        }
    }

    @Test
    void migrationChecksumHigherVersionAndIncompleteHistoryRequireRecovery(@TempDir Path temp) throws Exception {
        BootstrapSnapshot snapshot = new ProfileBootstrap(temp, CLOCK).open();
        Path ledger = temp.resolve("Profiles").resolve(snapshot.getProfileId()).resolve("ledger.db");

        try (Connection connection = new SqliteDatabase(ledger).open();
                PreparedStatement update = connection.prepareStatement(
                        "UPDATE schema_history SET checksum = ? WHERE version = 1")) {
            update.setString(1, "bad-checksum");
            update.executeUpdate();
        }
        assertThrows(PersistenceException.class, () -> new ProfileBootstrap(temp, CLOCK).open());

        try (Connection connection = new SqliteDatabase(ledger).open();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE schema_history SET checksum = 'restored'");
            statement.executeUpdate("DELETE FROM schema_history");
            statement.executeUpdate(
                    "INSERT INTO schema_history(version, description, checksum, installed_at, success) "
                            + "VALUES (2, 'future', 'future', '2026-09-12T08:30:00Z', 1)");
        }
        assertThrows(PersistenceException.class, () -> new ProfileBootstrap(temp, CLOCK).open());

        try (Connection connection = new SqliteDatabase(ledger).open();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM schema_history");
            statement.executeUpdate(
                    "INSERT INTO schema_history(version, description, checksum, installed_at, success) "
                            + "VALUES (1, 'bootstrap', 'bad', '2026-09-12T08:30:00Z', 0)");
        }
        assertThrows(PersistenceException.class, () -> new ProfileBootstrap(temp, CLOCK).open());
    }

    @Test
    void pathEscapeIsRejectedWithoutDeletingLedger(@TempDir Path temp) throws Exception {
        BootstrapSnapshot snapshot = new ProfileBootstrap(temp, CLOCK).open();
        Path catalog = temp.resolve("profiles.db");
        Path ledger = temp.resolve("Profiles").resolve(snapshot.getProfileId()).resolve("ledger.db");

        try (Connection connection = new SqliteDatabase(catalog).open();
                PreparedStatement update = connection.prepareStatement(
                        "UPDATE profile SET relative_directory = ? WHERE id = ?")) {
            update.setString(1, "Profiles/../outside");
            update.setString(2, snapshot.getProfileId());
            update.executeUpdate();
        }

        assertThrows(PersistenceException.class, () -> new ProfileBootstrap(temp, CLOCK).open());
        SystemStatusProvider recovery = ApplicationBootstrap.fromDirectory(temp, BuildMetadata.applicationVersion(), CLOCK);
        assertEquals("RECOVERY_REQUIRED", recovery.current().getState());
        assertTrue(Files.isRegularFile(ledger));
        assertFalse(Files.exists(temp.resolve("outside")));
    }

    @Test
    void transactionFailureRollsBackAndConnectionCanBeReused(@TempDir Path temp) throws Exception {
        Path database = temp.resolve("transaction.db");
        try (Connection connection = new SqliteDatabase(database).open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sample (id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
            TransactionRunner runner = new TransactionRunner();
            assertThrows(IllegalStateException.class, () -> runner.write(connection, tx -> {
                try (PreparedStatement insert = tx.prepareStatement("INSERT INTO sample(id, value) VALUES (1, 'bad')")) {
                    insert.executeUpdate();
                }
                throw new IllegalStateException("test rollback");
            }));
            assertEquals(0, countRows(connection, "sample"));
            runner.write(connection, tx -> {
                try (PreparedStatement insert = tx.prepareStatement("INSERT INTO sample(id, value) VALUES (1, 'good')")) {
                    insert.executeUpdate();
                }
                return null;
            });
            assertEquals(1, countRows(connection, "sample"));
            assertTrue(connection.getAutoCommit());
        }
    }

    @Test
    void statusProviderAndRealHttpExposeReadySnapshot(@TempDir Path temp) throws Exception {
        SystemStatusProvider provider = ApplicationBootstrap.fromDirectory(temp, BuildMetadata.applicationVersion(), CLOCK);
        SystemStatus status = provider.current();
        assertEquals("READY", status.getState());
        assertNotNull(status.getActiveProfileId());
        assertEquals(4, status.getSchemaVersion());
        assertEquals(0L, status.getDataRevision());

        Path web = temp.resolve("web");
        Files.createDirectories(web.resolve("assets"));
        Files.writeString(web.resolve("index.html"), "<h1>test</h1>", StandardCharsets.UTF_8);
        Files.writeString(web.resolve("assets/app.js"), "console.log('test');", StandardCharsets.UTF_8);
        ByteArrayOutputStream readiness = new ByteArrayOutputStream();
        LedgerHttpServer server = new LedgerHttpServer(
                TOKEN,
                new InetSocketAddress("127.0.0.1", 0),
                StaticResourceManifest.forDirectory(web,
                        Collections.singletonMap("index.html", "index.html")),
                provider,
                new PrintStream(readiness, true, StandardCharsets.UTF_8),
                2,
                2);
        try {
            server.start();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(server.origin() + "/api/v1/system/status"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"state\":\"READY\""));
            assertTrue(response.body().contains("\"schemaVersion\":4"));
            assertTrue(response.body().contains("\"dataRevision\":0"));
            assertFalse(response.body().contains(TOKEN));
            assertFalse(readiness.toString(StandardCharsets.UTF_8).contains(status.getActiveProfileId()));
        } finally {
            server.stop(java.time.Duration.ofSeconds(2));
        }
    }

    private static void assertCatalogShape(Path catalog, String profileId) throws Exception {
        assertEquals(Arrays.asList("catalog_processed_operation", "catalog_schema_history", "catalog_setting", "profile"),
                tableNames(catalog));
        assertEquals(Arrays.asList("idx_catalog_processed_operation_expires_at", "idx_profile_archived_last_opened"),
                indexNames(catalog));
        assertEquals(2, countRows(catalog, "catalog_schema_history"));
        assertEquals(0, countRows(catalog, "catalog_processed_operation"));
        try (Connection connection = new SqliteDatabase(catalog).open();
                PreparedStatement query = connection.prepareStatement(
                        "SELECT name, relative_directory, archived_at, revision FROM profile WHERE id = ?")) {
            query.setString(1, profileId);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals("默认空间", result.getString("name"));
                assertEquals("Profiles/" + profileId, result.getString("relative_directory"));
                assertEquals(null, result.getString("archived_at"));
                assertEquals(0, result.getInt("revision"));
            }
            try (PreparedStatement setting = connection.prepareStatement(
                    "SELECT revision FROM catalog_setting WHERE id = 1");
                    ResultSet result = setting.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0, result.getInt("revision"));
                assertFalse(result.next());
            }
        }
    }

    private static void assertCatalogConstraints(Path catalog, String profileId) throws Exception {
        try (Connection connection = new SqliteDatabase(catalog).open()) {
            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM catalog_setting WHERE id = 1");
                    PreparedStatement invalid = connection.prepareStatement(
                            "INSERT INTO catalog_setting(id, active_profile_id, updated_at) VALUES (1, ?, ?)");) {
                delete.executeUpdate();
                invalid.setString(1, "00000000-0000-4000-8000-000000000000");
                invalid.setString(2, "2026-09-12T08:30:00Z");
                assertThrows(SQLException.class, invalid::executeUpdate);
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            connection.setAutoCommit(false);
            String insertOperation = "INSERT INTO catalog_processed_operation "
                    + "(idempotency_key, http_method, canonical_path, request_hash, response_status, "
                    + "response_json, etag, location, completed_at, expires_at) "
                    + "VALUES (?, ?, '/api/v1/profiles', ?, 201, '{}', '\"0\"', "
                    + "'/api/v1/profiles/test', '2026-09-12T08:30:00Z', '2026-09-19T08:30:00Z')";
            try (PreparedStatement valid = connection.prepareStatement(insertOperation);
                    PreparedStatement invalidRevision = connection.prepareStatement(
                            "UPDATE catalog_setting SET revision = -1 WHERE id = 1")) {
                valid.setString(1, UUID.randomUUID().toString());
                valid.setString(2, "POST");
                valid.setString(3, "a".repeat(64));
                assertEquals(1, valid.executeUpdate());
                assertThrows(SQLException.class, valid::executeUpdate);
                assertThrows(SQLException.class, invalidRevision::executeUpdate);
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            assertTrue(countRows(connection, "profile") == 1);
            assertEquals(0, countRows(connection, "catalog_processed_operation"));
        }
    }

    private static void assertLedgerShape(Path ledger, String profileId) throws Exception {
        assertEquals(Arrays.asList("category", "category_record_type", "finance_record",
                        "financial_account", "ledger_meta", "ledger_setting", "processed_operation",
                        "schema_history"),
                tableNames(ledger));
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
                        "ux_financial_account_active_name"),
                indexNames(ledger));
        try (Connection connection = new SqliteDatabase(ledger).open();
                PreparedStatement query = connection.prepareStatement(
                        "SELECT profile_id, data_revision FROM ledger_meta WHERE id = 1")) {
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals(profileId, result.getString("profile_id"));
                assertEquals(0, result.getLong("data_revision"));
            }
            try (PreparedStatement setting = connection.prepareStatement(
                    "SELECT notifications_enabled, auto_backup_enabled, auto_backup_interval_days, "
                            + "auto_backup_retention_count, last_settings_section, currency_code, currency_symbol, "
                            + "safety_buffer_minor, hide_all_amounts, theme_name, revision FROM ledger_setting WHERE id = 1");
                    ResultSet result = setting.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0, result.getInt("notifications_enabled"));
                assertEquals(1, result.getInt("auto_backup_enabled"));
                assertEquals(7, result.getInt("auto_backup_interval_days"));
                assertEquals(10, result.getInt("auto_backup_retention_count"));
                assertEquals("GENERAL", result.getString("last_settings_section"));
                assertEquals("CNY", result.getString("currency_code"));
                assertEquals("¥", result.getString("currency_symbol"));
                assertEquals(300000, result.getLong("safety_buffer_minor"));
                assertEquals(0, result.getInt("hide_all_amounts"));
                assertEquals("WARM_COPPER", result.getString("theme_name"));
                assertEquals(0, result.getLong("revision"));
                assertFalse(result.next());
            }
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

    private static void assertOperationRejected(
            PreparedStatement insert,
            String key,
            String method,
            String path,
            String hash,
            int status,
            String completedAt,
            String expiresAt) throws Exception {
        setOperation(insert, key, method, path, hash, status, completedAt, expiresAt);
        assertThrows(SQLException.class, insert::executeUpdate);
    }

    private static void setOperation(
            PreparedStatement insert,
            String key,
            String method,
            String path,
            String hash,
            int status,
            String completedAt,
            String expiresAt) throws SQLException {
        insert.setString(1, key);
        insert.setString(2, method);
        insert.setString(3, path);
        insert.setString(4, hash);
        insert.setInt(5, status);
        insert.setString(6, completedAt);
        insert.setString(7, expiresAt);
    }

    private static java.util.List<String> tableNames(Path database) throws Exception {
        java.util.List<String> names = new java.util.ArrayList<>();
        try (Connection connection = new SqliteDatabase(database).open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")) {
            while (result.next()) {
                names.add(result.getString(1));
            }
        }
        return names;
    }

    private static java.util.List<String> indexNames(Path database) throws Exception {
        java.util.List<String> names = new java.util.ArrayList<>();
        try (Connection connection = new SqliteDatabase(database).open();
                Statement statement = connection.createStatement();
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
