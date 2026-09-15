package com.ledgerx.application.system;

import java.util.Collections;

/** Initial status until the persistence bootstrap supplies a real projection. */
public final class InitialSystemStatusProvider implements SystemStatusProvider {
    private final String applicationVersion;

    public InitialSystemStatusProvider(String applicationVersion) {
        if (applicationVersion == null || applicationVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("applicationVersion must not be blank");
        }
        this.applicationVersion = applicationVersion;
    }

    @Override
    public SystemStatus current() {
        return new SystemStatus(
                "1.0",
                applicationVersion,
                null,
                3,
                null,
                "STARTING",
                Collections.singletonList("system.status"),
                null);
    }
}
