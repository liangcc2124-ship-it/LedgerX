package com.ledgerx.persistence;

/** Sanitized persistence projection used by the application status provider. */
public final class BootstrapSnapshot {
    private final String profileId;
    private final int schemaVersion;
    private final long dataRevision;
    private final String setupState;
    private final String ledgerStartOn;

    public BootstrapSnapshot(String profileId, int schemaVersion, long dataRevision) {
        this(profileId, schemaVersion, dataRevision, "COMPLETED", null);
    }

    public BootstrapSnapshot(String profileId, int schemaVersion, long dataRevision,
            String setupState, String ledgerStartOn) {
        this.profileId = profileId;
        this.schemaVersion = schemaVersion;
        this.dataRevision = dataRevision;
        this.setupState = setupState;
        this.ledgerStartOn = ledgerStartOn;
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

    public String getSetupState() {
        return setupState;
    }

    public String getLedgerStartOn() {
        return ledgerStartOn;
    }
}
