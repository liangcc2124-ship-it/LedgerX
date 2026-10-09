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
import java.util.Map;

/** Bridges the persistence startup projection into the existing system status port. */
public final class ApplicationBootstrap {
    public static final String DATA_DIRECTORY_ENVIRONMENT_NAME = "LEDGERX_DATA_DIR";
    public static final String TEST_DATA_DIRECTORY_ENVIRONMENT_NAME = "LEDGERX_TEST_DATA_DIR";

    private ApplicationBootstrap() {
    }

    public static SystemStatusProvider fromEnvironment(String applicationVersion) {
        return runtimeFromEnvironment(applicationVersion);
    }

    public static ApplicationRuntime runtimeFromEnvironment(String applicationVersion) {
        try {
            Path dataDirectory = dataDirectoryFromEnvironment(System.getenv(), System.getProperty("user.home"));
            return runtimeFromDirectory(dataDirectory, applicationVersion, Clock.systemUTC());
        } catch (InvalidPathException ex) {
            return ApplicationRuntime.unavailable(recoveryProvider(applicationVersion));
        }
    }

    public static Path dataDirectoryFromEnvironment(Map<String, String> environment, String userHome) {
        String testDirectory = environment.get(TEST_DATA_DIRECTORY_ENVIRONMENT_NAME);
        if (testDirectory != null && !testDirectory.trim().isEmpty()) {
            return Paths.get(testDirectory);
        }
        String configured = environment.get(DATA_DIRECTORY_ENVIRONMENT_NAME);
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured);
        }
        String localAppData = environment.get("LOCALAPPDATA");
        if (localAppData != null && !localAppData.trim().isEmpty()) {
            return Paths.get(localAppData).resolve("LedgerX");
        }
        return Paths.get(userHome, "AppData", "Local", "LedgerX");
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
