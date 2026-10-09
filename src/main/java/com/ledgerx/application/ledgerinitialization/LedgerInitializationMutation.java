package com.ledgerx.application.ledgerinitialization;

public final class LedgerInitializationMutation {
    private final String idempotencyKey;
    private final String requestHash;

    public LedgerInitializationMutation(String idempotencyKey, String requestHash) {
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
}
