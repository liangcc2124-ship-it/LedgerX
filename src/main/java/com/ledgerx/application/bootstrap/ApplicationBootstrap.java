package com.ledgerx.application.bootstrap;

import com.ledgerx.application.system.InitialSystemStatusProvider;
import com.ledgerx.application.system.SystemStatus;
import com.ledgerx.application.system.SystemStatusProvider;
import com.ledgerx.application.profile.ProfileApplicationService;
import com.ledgerx.persistence.PersistenceException;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.Collections;

/** Bridges the persistence startup projection into the existing system status port. */
public final class ApplicationBootstrap {
    public static final String DATA_DIRECTORY_ENVIRONMENT_NAME = "LEDGERX_DATA_DIR";

    private ApplicationBootstrap() {
    }

    public static SystemStatusProvider fromEnvironment(String applicationVersion) {
        return runtimeFromEnvironment(applicationVersion);
    }

    public static ApplicationRuntime runtimeFromEnvironment(String applicationVersion) {
        String configured = System.getenv(DATA_DIRECTORY_ENVIRONMENT_NAME);
        if (configured == null || configured.trim().isEmpty()) {
            return ApplicationRuntime.unavailable(new InitialSystemStatusProvider(applicationVersion));
        }
        try {
            return runtimeFromDirectory(Paths.get(configured), applicationVersion, Clock.systemUTC());
        } catch (InvalidPathException ex) {
            return ApplicationRuntime.unavailable(recoveryProvider(applicationVersion));
        }
    }

    public static SystemStatusProvider fromDirectory(Path dataDirectory, String applicationVersion, Clock clock) {
        return runtimeFromDirectory(dataDirectory, applicationVersion, clock);
    }

    public static ApplicationRuntime runtimeFromDirectory(Path dataDirectory, String applicationVersion, Clock clock) {
        try {
            return ApplicationRuntime.ready(ProfileApplicationService.open(dataDirectory, applicationVersion, clock));
        } catch (PersistenceException | RuntimeException ex) {
            try {
                return ApplicationRuntime.unavailable(recoveryProvider(applicationVersion),
                        ProfileApplicationService.openRecoveryCatalog(dataDirectory, applicationVersion, clock));
            } catch (PersistenceException | RuntimeException ignored) {
                return ApplicationRuntime.unavailable(recoveryProvider(applicationVersion));
            }
        }
    }

    private static SystemStatusProvider recoveryProvider(String applicationVersion) {
        SystemStatus status = new SystemStatus(
                "1.0",
                applicationVersion,
                null,
                3,
                null,
                "RECOVERY_REQUIRED",
                Collections.singletonList("system.status"),
                null);
        return () -> status;
    }
}
