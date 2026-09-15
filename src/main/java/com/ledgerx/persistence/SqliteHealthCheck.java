package com.ledgerx.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Runs SQLite integrity checks without exposing SQL details to callers. */
public final class SqliteHealthCheck {
    private SqliteHealthCheck() {
    }

    public static void quick(Connection connection) throws PersistenceException {
        checkResult(connection, "quick_check");
        checkForeignKeys(connection);
    }

    public static void full(Connection connection) throws PersistenceException {
        checkResult(connection, "integrity_check");
        checkForeignKeys(connection);
    }

    private static void checkResult(Connection connection, String pragma) throws PersistenceException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA " + pragma)) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new PersistenceException("SQLite health check failed");
            }
        } catch (SQLException ex) {
            throw new PersistenceException("SQLite health check failed", ex);
        }
    }

    private static void checkForeignKeys(Connection connection) throws PersistenceException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (result.next()) {
                throw new PersistenceException("SQLite foreign key check failed");
            }
        } catch (SQLException ex) {
            throw new PersistenceException("SQLite foreign key check failed", ex);
        }
    }
}
