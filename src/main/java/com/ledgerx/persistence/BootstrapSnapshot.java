package com.ledgerx.persistence;

/** Sanitized persistence projection used by the application status provider. */
public final class BootstrapSnapshot {
    private final String profileId;
    private final int schemaVersion;
    private final long dataRevision;

    public BootstrapSnapshot(String profileId, int schemaVersion, long dataRevision) {
        this.profileId = profileId;
        this.schemaVersion = schemaVersion;
        this.dataRevision = dataRevision;
    }

    public String getProfileId() {
        return profileId;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public long getDataRevision() {
        return dataRevision;
    }
}
