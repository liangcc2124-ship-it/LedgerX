package com.ledgerx.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/** Owns catalog/profile startup and the safe filesystem boundary around profile ledgers. */
public final class ProfileBootstrap {
    private static final String CATALOG_V001 = "/db/catalog/migration/V001__bootstrap.sql";
    private static final String CATALOG_V002 = "/db/catalog/migration/V002__catalog_operations.sql";
    private static final String PROFILE_NAME = "默认空间";

    private final Path dataRoot;
    private final Path profilesRoot;
    private final Clock clock;
    private final LedgerBootstrap ledgerBootstrap;

    public ProfileBootstrap(Path dataRoot, Clock clock) {
        if (dataRoot == null) {
            throw new IllegalArgumentException("dataRoot is required");
        }
        this.dataRoot = dataRoot.toAbsolutePath().normalize();
        this.profilesRoot = this.dataRoot.resolve("Profiles").normalize();
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.ledgerBootstrap = new LedgerBootstrap(this.clock);
    }

    public BootstrapSnapshot open() throws PersistenceException {
        boolean catalogExisted = Files.exists(catalogFile(), LinkOption.NOFOLLOW_LINKS);
        try {
            prepareDirectories();
            if (Files.isSymbolicLink(catalogFile())) {
                throw new PersistenceException("catalog path is not safe");
            }
            try (Connection catalog = new SqliteDatabase(catalogFile()).open()) {
                new MigrationRunner(Arrays.asList(CATALOG_V001, CATALOG_V002),
                        "catalog_schema_history", clock).migrate(catalog);
                SqliteHealthCheck.quick(catalog);
                if (!catalogExisted && isEmptyCatalog(catalog)) {
                    return createDefaultProfile(catalog);
                }
                return reopenActiveProfile(catalog);
            }
        } catch (PersistenceException ex) {
            throw ex;
        } catch (IOException | SQLException | RuntimeException ex) {
            throw new PersistenceException("profile catalog could not be opened", ex);
        }
    }

    public Path getDataRoot() {
        return dataRoot;
    }

    public Path getCatalogFile() {
        return catalogFile();
    }

    /**
     * Resolves a catalog-owned profile directory only after applying the same
     * containment and symbolic-link checks used during bootstrap.  The caller
     * owns creation of the final profile directory and any resulting ledger.
     */
    public Path resolveProfileDirectory(String profileId, String relativeDirectory)
            throws PersistenceException {
        try {
            prepareDirectories();
            validateProfilePath(profileId, relativeDirectory);
            return dataRoot.resolve(relativeDirectory).normalize();
        } catch (IOException ex) {
            throw new PersistenceException("profile directory could not be prepared", ex);
        }
    }

    private BootstrapSnapshot createDefaultProfile(Connection catalog)
            throws PersistenceException {
        String profileId = UUID.randomUUID().toString();
        String relativeDirectory = "Profiles/" + profileId;
        Path profileDirectory = dataRoot.resolve(relativeDirectory).normalize();
        Path ledgerFile = profileDirectory.resolve("ledger.db").normalize();
        ensureProfilePath(profileId, relativeDirectory, profileDirectory);

        boolean directoryCreated = false;
        boolean ledgerExisted = Files.exists(ledgerFile, LinkOption.NOFOLLOW_LINKS);
        boolean autoCommit = true;
        try {
            Files.createDirectory(profileDirectory);
            directoryCreated = true;
            catalog.setAutoCommit(false);
            autoCommit = false;
            String now = Instant.now(clock).toString();
            try (PreparedStatement profile = catalog.prepareStatement(
                    "INSERT INTO profile "
                            + "(id, name, relative_directory, created_at, last_opened_at, revision) "
                            + "VALUES (?, ?, ?, ?, ?, 0)");
                    PreparedStatement setting = catalog.prepareStatement(
                            "INSERT INTO catalog_setting (id, active_profile_id, updated_at) VALUES (1, ?, ?)")) {
                profile.setString(1, profileId);
                profile.setString(2, PROFILE_NAME);
                profile.setString(3, relativeDirectory);
                profile.setString(4, now);
                profile.setString(5, now);
                profile.executeUpdate();
                setting.setString(1, profileId);
                setting.setString(2, now);
                setting.executeUpdate();
            }

            BootstrapSnapshot snapshot = ledgerBootstrap.open(ledgerFile, profileId);
            catalog.commit();
            catalog.setAutoCommit(true);
            return snapshot;
        } catch (PersistenceException | IOException | SQLException | RuntimeException ex) {
            rollback(catalog, autoCommit);
            cleanupNewArtifacts(ledgerFile, ledgerExisted, profileDirectory, directoryCreated);
            if (ex instanceof PersistenceException) {
                throw (PersistenceException) ex;
            }
            throw new PersistenceException("default profile could not be created", ex);
        }
    }

    private BootstrapSnapshot reopenActiveProfile(Connection catalog)
            throws PersistenceException {
        ProfileRecord profile = readActiveProfile(catalog);
        validateProfilePath(profile.id, profile.relativeDirectory);
        Path profileDirectory = dataRoot.resolve(profile.relativeDirectory).normalize();
        Path ledgerFile = profileDirectory.resolve("ledger.db").normalize();
        if (!Files.isRegularFile(ledgerFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new PersistenceException("active profile ledger is missing");
        }
        BootstrapSnapshot snapshot = ledgerBootstrap.open(ledgerFile, profile.id);
        boolean autoCommit = true;
        try {
            catalog.setAutoCommit(false);
            autoCommit = false;
            try (PreparedStatement update = catalog.prepareStatement(
                    "UPDATE profile SET last_opened_at = ? WHERE id = ?")) {
                update.setString(1, Instant.now(clock).toString());
                update.setString(2, profile.id);
                if (update.executeUpdate() != 1) {
                    throw new PersistenceException("active profile disappeared");
                }
            }
            catalog.commit();
            catalog.setAutoCommit(true);
        } catch (PersistenceException | SQLException ex) {
            rollback(catalog, autoCommit);
            if (ex instanceof PersistenceException) {
                throw (PersistenceException) ex;
            }
            throw new PersistenceException("active profile timestamp could not be updated", ex);
        }
        return snapshot;
    }

    private ProfileRecord readActiveProfile(Connection catalog) throws PersistenceException {
        try (PreparedStatement setting = catalog.prepareStatement(
                "SELECT active_profile_id FROM catalog_setting WHERE id = 1");
                ResultSet settingResult = setting.executeQuery()) {
            if (!settingResult.next()) {
                throw new PersistenceException("active profile is not configured");
            }
            String activeId = settingResult.getString(1);
            if (settingResult.next() || activeId == null) {
                throw new PersistenceException("catalog setting is invalid");
            }
            try (PreparedStatement profile = catalog.prepareStatement(
                    "SELECT id, relative_directory, archived_at FROM profile WHERE id = ?")) {
                profile.setString(1, activeId);
                try (ResultSet result = profile.executeQuery()) {
                    if (!result.next() || result.getString("archived_at") != null) {
                        throw new PersistenceException("active profile is unavailable");
                    }
                    return new ProfileRecord(result.getString("id"), result.getString("relative_directory"));
                }
            }
        } catch (SQLException ex) {
            throw new PersistenceException("active profile could not be read", ex);
        }
    }

    private boolean isEmptyCatalog(Connection catalog) throws SQLException {
        try (PreparedStatement profile = catalog.prepareStatement("SELECT COUNT(*) FROM profile");
                ResultSet profiles = profile.executeQuery();
                PreparedStatement setting = catalog.prepareStatement("SELECT COUNT(*) FROM catalog_setting");
                ResultSet settings = setting.executeQuery()) {
            profiles.next();
            settings.next();
            return profiles.getInt(1) == 0 && settings.getInt(1) == 0;
        }
    }

    private void prepareDirectories() throws IOException, PersistenceException {
        if (Files.exists(dataRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(dataRoot)) {
            throw new PersistenceException("data directory is not a directory");
        }
        Files.createDirectories(dataRoot);
        if (Files.isSymbolicLink(dataRoot)) {
            throw new PersistenceException("data directory is not safe");
        }
        if (Files.exists(profilesRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(profilesRoot)) {
            throw new PersistenceException("profiles directory is not a directory");
        }
        Files.createDirectories(profilesRoot);
        if (Files.isSymbolicLink(profilesRoot)) {
            throw new PersistenceException("profiles directory is not safe");
        }
    }

    private void ensureProfilePath(String profileId, String relativeDirectory, Path profileDirectory)
            throws PersistenceException {
        validateUuid(profileId);
        validateProfilePath(profileId, relativeDirectory);
        if (Files.exists(profileDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new PersistenceException("profile directory already exists");
        }
    }

    private void validateProfilePath(String profileId, String relativeDirectory) throws PersistenceException {
        validateUuid(profileId);
        if (relativeDirectory == null) {
            throw new PersistenceException("profile directory is missing");
        }
        Path relative;
        try {
            relative = Paths.get(relativeDirectory);
        } catch (RuntimeException ex) {
            throw new PersistenceException("profile directory is invalid", ex);
        }
        if (relative.isAbsolute() || !relative.normalize().equals(relative)
                || relative.getNameCount() != 2
                || !"Profiles".equals(relative.getName(0).toString())
                || !profileId.equals(relative.getName(1).toString())) {
            throw new PersistenceException("profile directory escapes Profiles");
        }
        Path resolved = dataRoot.resolve(relative).normalize();
        if (!resolved.startsWith(profilesRoot) || !resolved.equals(profilesRoot.resolve(profileId))) {
            throw new PersistenceException("profile directory escapes Profiles");
        }
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(resolved)) {
            throw new PersistenceException("profile directory is a symbolic link");
        }
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new PersistenceException("profile directory is not a directory");
        }
    }

    private static void validateUuid(String value) throws PersistenceException {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new PersistenceException("profile id is not lowercase UUID");
            }
        } catch (IllegalArgumentException ex) {
            throw new PersistenceException("profile id is invalid", ex);
        }
    }

    private static void rollback(Connection connection, boolean autoCommit) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original failure.
        }
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
            // Preserve the original failure.
        }
    }

    private static void cleanupNewArtifacts(
            Path ledgerFile,
            boolean ledgerExisted,
            Path profileDirectory,
            boolean directoryCreated) {
        if (!ledgerExisted && Files.isRegularFile(ledgerFile, LinkOption.NOFOLLOW_LINKS)) {
            try {
                if (Files.size(ledgerFile) == 0L) {
                    Files.deleteIfExists(ledgerFile);
                }
            } catch (IOException ignored) {
                // Leave a non-empty or otherwise unremovable artifact for recovery inspection.
            }
        }
        if (directoryCreated) {
            try {
                Files.deleteIfExists(profileDirectory);
            } catch (IOException ignored) {
                // Never recursively delete data during compensation.
            }
        }
    }

    private Path catalogFile() {
        return dataRoot.resolve("profiles.db").normalize();
    }

    private static final class ProfileRecord {
        private final String id;
        private final String relativeDirectory;

        private ProfileRecord(String id, String relativeDirectory) {
            this.id = id;
            this.relativeDirectory = relativeDirectory;
        }
    }
}
