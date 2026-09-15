package com.ledgerx.application.profile;

/** Immutable idempotency identity after the HTTP adapter canonicalizes its accepted request fields. */
public final class ProfileMutation {
    private final String idempotencyKey;
    private final String httpMethod;
    private final String canonicalPath;
    private final String requestHash;

    public ProfileMutation(String idempotencyKey, String httpMethod, String canonicalPath, String requestHash) {
        this.idempotencyKey = idempotencyKey;
        this.httpMethod = httpMethod;
        this.canonicalPath = canonicalPath;
        this.requestHash = requestHash;
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public String getHttpMethod() { return httpMethod; }
    public String getCanonicalPath() { return canonicalPath; }
    public String getRequestHash() { return requestHash; }
}
