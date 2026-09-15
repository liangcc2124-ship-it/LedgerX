package com.ledgerx.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;

/** Creates or reopens one profile ledger and applies the ordered ledger schema. */
public final class LedgerBootstrap {
    private static final String LEDGER_V001 = "/db/ledger/migration/V001__bootstrap.sql";
    private static final String LEDGER_V002 = "/db/ledger/migration/V002__ledger_settings.sql";
    private static final String LEDGER_V003 = "/db/ledger/migration/V003__ledger_core_facts.sql";
    private static final String LEDGER_V004 = "/db/ledger/migration/V004__core_catalog_seed.sql";

    private final Clock clock;

    public LedgerBootstrap(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public BootstrapSnapshot open(Path file, String profileId) throws PersistenceException {
        if (file == null || profileId == null || profileId.trim().isEmpty()) {
            throw new IllegalArgumentException("ledger file and profile id are required");
        }
        Path normalized = file.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)) {
            throw new PersistenceException("ledger path is not safe");
        }
        boolean existed = Files.exists(normalized, LinkOption.NOFOLLOW_LINKS);
        try (Connection connection = new SqliteDatabase(normalized).open()) {
            int schemaVersion = new MigrationRunner(Arrays.asList(LEDGER_V001, LEDGER_V002, LEDGER_V003, LEDGER_V004),
                    "schema_history", clock).migrate(connection);
            if (!existed) {
                insertMeta(connection, profileId);
            } else {
                validateMeta(connection, profileId);
            }
            SqliteHealthCheck.quick(connection);
            return readSnapshot(connection, profileId, schemaVersion);
        } catch (IOException | SQLException ex) {
            throw new PersistenceException("ledger database could not be opened", ex);
        }
    }

    public void fullHealth(Path file) throws PersistenceException {
        try (Connection connection = new SqliteDatabase(file).open()) {
            SqliteHealthCheck.full(connection);
        } catch (IOException | SQLException ex) {
            throw new PersistenceException("ledger database health check failed", ex);
        }
    }

    private void insertMeta(Connection connection, String profileId) throws PersistenceException {
        String now = Instant.now(clock).toString();
        boolean autoCommit = true;
        try {
            autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ledger_meta "
                            + "(id, profile_id, created_at, updated_at, data_revision) "
                            + "VALUES (1, ?, ?, ?, 0)");
                    PreparedStatement setting = connection.prepareStatement(
                            "UPDATE ledger_setting SET updated_at = ? WHERE id = 1")) {
                statement.setString(1, profileId);
                statement.setString(2, now);
                statement.setString(3, now);
                statement.executeUpdate();
                setting.setString(1, now);
                if (setting.executeUpdate() != 1) {
                    throw new PersistenceException("ledger settings could not be initialized");
                }
            }
            connection.commit();
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ex) {
            rollback(connection, autoCommit);
            throw new PersistenceException("ledger metadata could not be created", ex);
        }
    }

    private void validateMeta(Connection connection, String profileId) throws PersistenceException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT profile_id FROM ledger_meta WHERE id = 1");
                ResultSet result = statement.executeQuery()) {
            if (!result.next() || !profileId.equals(result.getString(1)) || result.next()) {
                throw new PersistenceException("ledger metadata does not match active profile");
            }
        } catch (SQLException ex) {
            throw new PersistenceException("ledger metadata could not be read", ex);
        }
    }

    private BootstrapSnapshot readSnapshot(Connection connection, String profileId, int schemaVersion)
            throws PersistenceException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT data_revision FROM ledger_meta WHERE id = 1");
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new PersistenceException("ledger metadata is missing");
            }
            return new BootstrapSnapshot(profileId, schemaVersion, result.getLong(1));
        } catch (SQLException ex) {
            throw new PersistenceException("ledger metadata could not be read", ex);
        }
    }

    private static void rollback(Connection connection, boolean autoCommit) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original failure.
        }
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
            // Preserve the original failure.
        }
    }
}
