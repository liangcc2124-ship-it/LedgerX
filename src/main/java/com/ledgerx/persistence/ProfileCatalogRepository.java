package com.ledgerx.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** SQL-only access to the profile catalog; transaction ownership stays with the application service. */
public final class ProfileCatalogRepository {
    private final Path catalogFile;

    public ProfileCatalogRepository(Path catalogFile) {
        if (catalogFile == null) {
            throw new IllegalArgumentException("catalogFile is required");
        }
        this.catalogFile = catalogFile.toAbsolutePath().normalize();
    }

    public Connection openConnection() throws PersistenceException {
        try {
            return new SqliteDatabase(catalogFile).open();
        } catch (IOException | SQLException ex) {
            throw new PersistenceException("profile catalog could not be opened", ex);
        }
    }

    public CatalogState readState(Connection connection) throws PersistenceException {
        try (PreparedStatement setting = connection.prepareStatement(
                "SELECT active_profile_id, revision FROM catalog_setting WHERE id = 1");
                ResultSet settingResult = setting.executeQuery()) {
            if (!settingResult.next() || settingResult.getString("active_profile_id") == null) {
                throw new PersistenceException("active profile is not configured");
            }
            String activeProfileId = settingResult.getString("active_profile_id");
            long revision = settingResult.getLong("revision");
            if (settingResult.next()) {
                throw new PersistenceException("catalog setting is invalid");
            }
            List<CatalogProfile> profiles = new ArrayList<>();
            try (PreparedStatement profilesStatement = connection.prepareStatement(
                    "SELECT id, name, relative_directory, created_at, last_opened_at, archived_at, revision "
                            + "FROM profile ORDER BY id");
                    ResultSet profilesResult = profilesStatement.executeQuery()) {
                while (profilesResult.next()) {
                    profiles.add(new CatalogProfile(
                            profilesResult.getString("id"),
                            profilesResult.getString("name"),
                            profilesResult.getString("relative_directory"),
                            profilesResult.getString("created_at"),
                            profilesResult.getString("last_opened_at"),
                            profilesResult.getString("archived_at"),
                            profilesResult.getLong("revision")));
                }
            }
            return new CatalogState(activeProfileId, revision, profiles);
        } catch (SQLException ex) {
            throw new PersistenceException("profile catalog could not be read", ex);
        }
    }

    public CatalogOperation findCatalogOperation(Connection connection, String key, Instant now)
            throws PersistenceException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT idempotency_key, http_method, canonical_path, request_hash, response_status, "
                        + "response_json, etag, location, completed_at, expires_at "
                        + "FROM catalog_processed_operation WHERE idempotency_key = ? AND expires_at > ?")) {
            statement.setString(1, key);
            statement.setString(2, now.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new CatalogOperation(
                        result.getString("idempotency_key"),
                        result.getString("http_method"),
                        result.getString("canonical_path"),
                        result.getString("request_hash"),
                        result.getInt("response_status"),
                        result.getString("response_json"),
                        result.getString("etag"),
                        result.getString("location"),
                        result.getString("completed_at"),
                        result.getString("expires_at"));
            }
        } catch (SQLException ex) {
            throw new PersistenceException("catalog operation could not be read", ex);
        }
    }

    public void removeExpiredOperations(Connection connection, Instant now) throws PersistenceException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM catalog_processed_operation WHERE expires_at <= ?")) {
            statement.setString(1, now.toString());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new PersistenceException("expired catalog operations could not be removed", ex);
        }
    }

    public void insertProfile(Connection connection, CatalogProfile profile) throws PersistenceException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO profile (id, name, relative_directory, created_at, last_opened_at, revision) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, profile.getId());
            statement.setString(2, profile.getName());
            statement.setString(3, profile.getRelativeDirectory());
            statement.setString(4, profile.getCreatedAt());
            statement.setString(5, profile.getLastOpenedAt());
            statement.setLong(6, profile.getRevision());
            if (statement.executeUpdate() != 1) {
                throw new PersistenceException("profile could not be created");
            }
        } catch (SQLException ex) {
            throw new PersistenceException("profile could not be created", ex);
        }
    }

    public void incrementProfileRevision(Connection connection, String profileId) throws PersistenceException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE profile SET revision = revision + 1 WHERE id = ?")) {
            statement.setString(1, profileId);
            if (statement.executeUpdate() != 1) {
                throw new PersistenceException("profile disappeared");
            }
        } catch (SQLException ex) {
            throw new PersistenceException("profile revision could not be updated", ex);
        }
    }

    public void activateProfile(Connection connection, String profileId, long expectedCatalogRevision, String now)
            throws PersistenceException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE catalog_setting SET active_profile_id = ?, updated_at = ?, revision = revision + 1 "
                        + "WHERE id = 1 AND revision = ?")) {
            statement.setString(1, profileId);
            statement.setString(2, now);
            statement.setLong(3, expectedCatalogRevision);
            if (statement.executeUpdate() != 1) {
                throw new PersistenceException("catalog changed unexpectedly");
            }
        } catch (SQLException ex) {
            throw new PersistenceException("active profile could not be updated", ex);
        }
    }

    public void touchAndIncrementProfile(Connection connection, String profileId, String now)
            throws PersistenceException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE profile SET last_opened_at = ?, revision = revision + 1 WHERE id = ?")) {
            statement.setString(1, now);
            statement.setString(2, profileId);
            if (statement.executeUpdate() != 1) {
                throw new PersistenceException("profile disappeared");
            }
        } catch (SQLException ex) {
            throw new PersistenceException("profile could not be activated", ex);
        }
    }

    public void archiveAndIncrementProfile(Connection connection, String profileId, String now)
            throws PersistenceException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE profile SET archived_at = ?, revision = revision + 1 "
                        + "WHERE id = ? AND archived_at IS NULL")) {
            statement.setString(1, now);
            statement.setString(2, profileId);
            if (statement.executeUpdate() != 1) {
                throw new PersistenceException("profile could not be archived");
            }
        } catch (SQLException ex) {
            throw new PersistenceException("profile could not be archived", ex);
        }
    }

    public void insertCatalogOperation(Connection connection, CatalogOperation operation) throws PersistenceException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO catalog_processed_operation "
                        + "(idempotency_key, http_method, canonical_path, request_hash, response_status, "
                        + "response_json, etag, location, completed_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, operation.getIdempotencyKey());
            statement.setString(2, operation.getHttpMethod());
            statement.setString(3, operation.getCanonicalPath());
            statement.setString(4, operation.getRequestHash());
            statement.setInt(5, operation.getResponseStatus());
            statement.setString(6, operation.getResponseJson());
            statement.setString(7, operation.getEtag());
            statement.setString(8, operation.getLocation());
            statement.setString(9, operation.getCompletedAt());
            statement.setString(10, operation.getExpiresAt());
            if (statement.executeUpdate() != 1) {
                throw new PersistenceException("catalog operation could not be recorded");
            }
        } catch (SQLException ex) {
            throw new PersistenceException("catalog operation could not be recorded", ex);
        }
    }

    public static final class CatalogState {
        private final String activeProfileId;
        private final long revision;
        private final List<CatalogProfile> profiles;

        private CatalogState(String activeProfileId, long revision, List<CatalogProfile> profiles) {
            this.activeProfileId = activeProfileId;
            this.revision = revision;
            this.profiles = Collections.unmodifiableList(new ArrayList<>(profiles));
        }

        public String getActiveProfileId() { return activeProfileId; }
        public long getRevision() { return revision; }
        public List<CatalogProfile> getProfiles() { return profiles; }

        public CatalogProfile findProfile(String id) {
            for (CatalogProfile profile : profiles) {
                if (profile.getId().equals(id)) {
                    return profile;
                }
            }
            return null;
        }
    }

    public static final class CatalogProfile {
        private final String id;
        private final String name;
        private final String relativeDirectory;
        private final String createdAt;
        private final String lastOpenedAt;
        private final String archivedAt;
        private final long revision;

        public CatalogProfile(String id, String name, String relativeDirectory, String createdAt,
                String lastOpenedAt, String archivedAt, long revision) {
            this.id = id;
            this.name = name;
            this.relativeDirectory = relativeDirectory;
            this.createdAt = createdAt;
            this.lastOpenedAt = lastOpenedAt;
            this.archivedAt = archivedAt;
            this.revision = revision;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getRelativeDirectory() { return relativeDirectory; }
        public String getCreatedAt() { return createdAt; }
        public String getLastOpenedAt() { return lastOpenedAt; }
        public String getArchivedAt() { return archivedAt; }
        public long getRevision() { return revision; }
    }

    public static final class CatalogOperation {
        private final String idempotencyKey;
        private final String httpMethod;
        private final String canonicalPath;
        private final String requestHash;
        private final int responseStatus;
        private final String responseJson;
        private final String etag;
        private final String location;
        private final String completedAt;
        private final String expiresAt;

        public CatalogOperation(String idempotencyKey, String httpMethod, String canonicalPath, String requestHash,
                int responseStatus, String responseJson, String etag, String location,
                String completedAt, String expiresAt) {
            this.idempotencyKey = idempotencyKey;
            this.httpMethod = httpMethod;
            this.canonicalPath = canonicalPath;
            this.requestHash = requestHash;
            this.responseStatus = responseStatus;
            this.responseJson = responseJson;
            this.etag = etag;
            this.location = location;
            this.completedAt = completedAt;
            this.expiresAt = expiresAt;
        }

        public String getIdempotencyKey() { return idempotencyKey; }
        public String getHttpMethod() { return httpMethod; }
        public String getCanonicalPath() { return canonicalPath; }
        public String getRequestHash() { return requestHash; }
        public int getResponseStatus() { return responseStatus; }
        public String getResponseJson() { return responseJson; }
        public String getEtag() { return etag; }
        public String getLocation() { return location; }
        public String getCompletedAt() { return completedAt; }
        public String getExpiresAt() { return expiresAt; }
    }
}
