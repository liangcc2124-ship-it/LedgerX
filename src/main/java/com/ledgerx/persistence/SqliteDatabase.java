package com.ledgerx.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Opens a SQLite file with the connection invariants required by LedgerX. */
public final class SqliteDatabase {
    public static final int BUSY_TIMEOUT_MILLIS = 5000;

    private final Path file;

    public SqliteDatabase(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("file is required");
        }
        this.file = file.toAbsolutePath().normalize();
    }

    public Connection open() throws SQLException, IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try {
            configure(connection);
            return connection;
        } catch (SQLException | RuntimeException ex) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Preserve the configuration failure.
            }
            throw ex;
        }
    }

    public Path getFile() {
        return file;
    }

    private static void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MILLIS);
        }
        if (pragmaInt(connection, "foreign_keys") != 1) {
            throw new SQLException("SQLite foreign_keys pragma was not enabled");
        }
        if (pragmaInt(connection, "busy_timeout") != BUSY_TIMEOUT_MILLIS) {
            throw new SQLException("SQLite busy_timeout pragma was not applied");
        }
    }

    private static int pragmaInt(Connection connection, String pragma) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA " + pragma)) {
            if (!result.next()) {
                throw new SQLException("SQLite pragma returned no value: " + pragma);
            }
            return result.getInt(1);
        }
    }
}
