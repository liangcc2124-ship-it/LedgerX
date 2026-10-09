package com.ledgerx.application.metrics;

public final class DashboardMutation {
    private final String idempotencyKey;
    private final String httpMethod;
    private final String canonicalPath;
    private final String requestHash;

    public DashboardMutation(String idempotencyKey, String httpMethod, String canonicalPath, String requestHash) {
        this.idempotencyKey = idempotencyKey; this.httpMethod = httpMethod;
        this.canonicalPath = canonicalPath; this.requestHash = requestHash;
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public String getHttpMethod() { return httpMethod; }
    public String getCanonicalPath() { return canonicalPath; }
    public String getRequestHash() { return requestHash; }
}
