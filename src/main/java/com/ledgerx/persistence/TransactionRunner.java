package com.ledgerx.persistence;

import java.sql.Connection;
import java.sql.SQLException;

/** Keeps transaction ownership in the application boundary rather than repositories. */
public final class TransactionRunner {
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws Exception;
    }

    public <T> T write(Connection connection, Work<T> work) throws Exception {
        if (connection == null || work == null) {
            throw new IllegalArgumentException("connection and work are required");
        }
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = work.run(connection);
            connection.commit();
            connection.setAutoCommit(autoCommit);
            return result;
        } catch (Exception | Error ex) {
            rollback(connection, autoCommit);
            throw ex;
        }
    }

    private static void rollback(Connection connection, boolean autoCommit) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the application failure.
        }
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
            // Preserve the application failure.
        }
    }
}
