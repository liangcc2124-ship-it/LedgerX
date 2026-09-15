package com.ledgerx.persistence;

/** A recoverable persistence/bootstrap failure without exposing filesystem details to HTTP. */
public final class PersistenceException extends Exception {
    public PersistenceException(String message) {
        super(message);
    }

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
