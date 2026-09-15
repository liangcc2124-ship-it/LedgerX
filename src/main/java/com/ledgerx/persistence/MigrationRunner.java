package com.ledgerx.persistence;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies the small, ordered, checksummed SQL migration set without a framework. */
public final class MigrationRunner {
    private static final Pattern MIGRATION_NAME = Pattern.compile("^V([0-9]{3})__([^/]+)\\.sql$");

    private final List<String> resources;
    private final String historyTable;
    private final Clock clock;

    public MigrationRunner(String resource, String historyTable, Clock clock) {
        this(Collections.singletonList(require(resource, "resource")), historyTable, clock);
    }

    public MigrationRunner(List<String> resources, String historyTable, Clock clock) {
        if (resources == null || resources.isEmpty()) {
            throw new IllegalArgumentException("at least one migration resource is required");
        }
        List<String> copy = new ArrayList<>(resources.size());
        for (String resource : resources) {
            copy.add(require(resource, "resource"));
        }
        this.resources = Collections.unmodifiableList(copy);
        this.historyTable = require(historyTable, "historyTable");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public int migrate(Connection connection) throws PersistenceException {
        List<Migration> migrations;
        try {
            migrations = loadMigrations();
        } catch (IOException ex) {
            throw new PersistenceException("migration resource unavailable", ex);
        }

        boolean autoCommit = true;
        try {
            autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            int installedVersion = validateExistingHistory(connection, migrations);
            for (Migration migration : migrations) {
                if (migration.version > installedVersion) {
                    executeSql(connection, migration.sql);
                    insertHistory(connection, migration);
                }
            }
            connection.commit();
            connection.setAutoCommit(autoCommit);
            return migrations.get(migrations.size() - 1).version;
        } catch (PersistenceException | SQLException | RuntimeException ex) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // Preserve the original migration failure.
            }
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException ignored) {
                // Preserve the original migration failure.
            }
            if (ex instanceof PersistenceException) {
                throw (PersistenceException) ex;
            }
            throw new PersistenceException("migration failed", ex);
        }
    }

    private int validateExistingHistory(Connection connection, List<Migration> migrations)
            throws SQLException, PersistenceException {
        if (!tableExists(connection, historyTable)) {
            return 0;
        }
        String sql = "SELECT version, checksum, success FROM " + historyTable + " ORDER BY version";
        int expectedVersion = 1;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                int version = result.getInt("version");
                String checksum = result.getString("checksum");
                int success = result.getInt("success");
                if (version != expectedVersion) {
                    throw new PersistenceException("database migration history has a gap");
                }
                if (version > migrations.size()) {
                    throw new PersistenceException("database schema is newer than this application");
                }
                if (success != 1) {
                    throw new PersistenceException("database migration is incomplete");
                }
                Migration migration = migrations.get(version - 1);
                if (!migration.checksum.equals(checksum)) {
                    throw new PersistenceException("successful migration checksum changed");
                }
                expectedVersion++;
            }
        }
        return expectedVersion - 1;
    }

    private void executeSql(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String statementSql : sql.split(";")) {
                String trimmed = statementSql.trim();
                if (!trimmed.isEmpty()) {
                    statement.execute(trimmed);
                }
            }
        }
    }

    private void insertHistory(Connection connection, Migration migration) throws SQLException {
        String insertSql = "INSERT INTO " + historyTable
                + " (version, description, checksum, installed_at, success) VALUES (?, ?, ?, ?, 1)";
        try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
            insert.setInt(1, migration.version);
            insert.setString(2, migration.description);
            insert.setString(3, migration.checksum);
            insert.setString(4, Instant.now(clock).toString());
            insert.executeUpdate();
        }
    }

    private List<Migration> loadMigrations() throws IOException {
        List<Migration> migrations = new ArrayList<>(resources.size());
        int expectedVersion = 1;
        for (String resource : resources) {
            Migration migration = loadMigration(resource);
            if (migration.version != expectedVersion) {
                throw new IOException("migration resources must be contiguous and ordered from V001");
            }
            migrations.add(migration);
            expectedVersion++;
        }
        return migrations;
    }

    private Migration loadMigration(String resource) throws IOException {
        String name = fileName(resource);
        Matcher matcher = MIGRATION_NAME.matcher(name);
        if (!matcher.matches()) {
            throw new IOException("invalid migration resource name");
        }
        int version;
        try {
            version = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            throw new IOException("migration version is invalid", ex);
        }
        try (InputStream input = MigrationRunner.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("missing migration resource");
            }
            byte[] bytes = readAll(input);
            String sql = new String(bytes, StandardCharsets.UTF_8);
            return new Migration(version, matcher.group(2), sha256(bytes), sql);
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

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 unavailable", ex);
        }
    }

    private static String fileName(String resource) {
        int slash = resource.lastIndexOf('/');
        return slash >= 0 ? resource.substring(slash + 1) : resource;
    }

    private static String require(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static final class Migration {
        private final int version;
        private final String description;
        private final String checksum;
        private final String sql;

        private Migration(int version, String description, String checksum, String sql) {
            this.version = version;
            this.description = description;
            this.checksum = checksum;
            this.sql = sql;
        }
    }
}
