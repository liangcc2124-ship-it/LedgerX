package com.ledgerx.application.system;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Transport-neutral status projection exposed by the system application port. */
public final class SystemStatus {
    private final String apiVersion;
    private final String applicationVersion;
    private final Integer schemaVersion;
    private final int backupFormatVersion;
    private final String activeProfileId;
    private final String state;
    private final List<String> capabilities;
    private final Long dataRevision;

    public SystemStatus(
            String apiVersion,
            String applicationVersion,
            Integer schemaVersion,
            int backupFormatVersion,
            String activeProfileId,
            String state,
            List<String> capabilities,
            Long dataRevision) {
        this.apiVersion = apiVersion;
        this.applicationVersion = applicationVersion;
        this.schemaVersion = schemaVersion;
        this.backupFormatVersion = backupFormatVersion;
        this.activeProfileId = activeProfileId;
        this.state = state;
        this.capabilities = Collections.unmodifiableList(new ArrayList<>(capabilities));
        this.dataRevision = dataRevision;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public String getApplicationVersion() {
        return applicationVersion;
    }

    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    public int getBackupFormatVersion() {
        return backupFormatVersion;
    }

    public String getActiveProfileId() {
        return activeProfileId;
    }

    public String getState() {
        return state;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    public Long getDataRevision() {
        return dataRevision;
    }
}
