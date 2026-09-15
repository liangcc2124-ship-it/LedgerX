package com.ledgerx.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/** SQL-only access to one ledger's settings and its ledger-scoped idempotency records. */
public final class LedgerSettingsRepository {
    public SettingsState readState(Connection connection) throws PersistenceException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT s.notifications_enabled, s.auto_backup_enabled, s.auto_backup_interval_days, "
                        + "s.auto_backup_retention_count, s.last_auto_backup_at, s.last_settings_section, "
                        + "s.currency_code, s.currency_symbol, s.safety_buffer_minor, s.hide_all_amounts, "
                        + "s.theme_name, s.custom_theme_css, s.revision, s.updated_at, m.data_revision "
                        + "FROM ledger_setting s JOIN ledger_meta m ON m.id = 1 WHERE s.id = 1");
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new PersistenceException("ledger settings are missing");
            }
            SettingsRecord settings = new SettingsRecord(
                    result.getInt("notifications_enabled") == 1,
                    result.getInt("auto_backup_enabled") == 1,
                    result.getInt("auto_backup_interval_days"),
                    result.getInt("auto_backup_retention_count"),
                    result.getString("last_auto_backup_at"),
                    result.getString("last_settings_section"),
                    result.getString("currency_code"),
                    result.getString("currency_symbol"),
                    result.getLong("safety_buffer_minor"),
                    result.getInt("hide_all_amounts") == 1,
                    result.getString("theme_name"),
                    result.getString("custom_theme_css"),
                    result.getLong("revision"),
                    result.getString("updated_at"));
            long dataRevision = result.getLong("data_revision");
            if (result.next()) {
                throw new PersistenceException("ledger settings are not a single row");
            }
            return new SettingsState(settings, dataRevision);
        } catch (SQLException ex) {
            throw new PersistenceException("ledger settings could not be read", ex);
        }
    }

    public LedgerOperation findOperation(Connection connection, String key, Instant now) throws PersistenceException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT idempotency_key, http_method, canonical_path, request_hash, response_status, response_json, "
                        + "profile_id, completed_at, expires_at FROM processed_operation "
                        + "WHERE idempotency_key = ? AND expires_at > ?")) {
            statement.setString(1, key);
            statement.setString(2, now.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new LedgerOperation(result.getString("idempotency_key"), result.getString("http_method"),
                        result.getString("canonical_path"), result.getString("request_hash"),
                        result.getInt("response_status"), result.getString("response_json"),
                        result.getString("profile_id"), result.getString("completed_at"), result.getString("expires_at"));
            }
        } catch (SQLException ex) {
            throw new PersistenceException("ledger operation could not be read", ex);
        }
    }

    public void removeExpiredOperations(Connection connection, Instant now) throws PersistenceException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM processed_operation WHERE expires_at <= ?")) {
            statement.setString(1, now.toString());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new PersistenceException("expired ledger operations could not be removed", ex);
        }
    }

    public void updateSettings(Connection connection, SettingsRecord value, long expectedRevision) throws PersistenceException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ledger_setting SET notifications_enabled = ?, auto_backup_enabled = ?, "
                        + "auto_backup_interval_days = ?, auto_backup_retention_count = ?, last_settings_section = ?, "
                        + "safety_buffer_minor = ?, hide_all_amounts = ?, theme_name = ?, custom_theme_css = ?, "
                        + "revision = ?, updated_at = ? WHERE id = 1 AND revision = ?")) {
            statement.setInt(1, value.isNotificationsEnabled() ? 1 : 0);
            statement.setInt(2, value.isAutoBackupEnabled() ? 1 : 0);
            statement.setInt(3, value.getAutoBackupIntervalDays());
            statement.setInt(4, value.getAutoBackupRetentionCount());
            statement.setString(5, value.getLastSettingsSection());
            statement.setLong(6, value.getSafetyBufferMinor());
            statement.setInt(7, value.isHideAllAmounts() ? 1 : 0);
            statement.setString(8, value.getThemeName());
            statement.setString(9, value.getCustomThemeCss());
            statement.setLong(10, value.getRevision());
            statement.setString(11, value.getUpdatedAt());
            statement.setLong(12, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new PersistenceException("ledger settings changed unexpectedly");
            }
        } catch (SQLException ex) {
            throw new PersistenceException("ledger settings could not be updated", ex);
        }
    }

    public void advanceLedgerMeta(Connection connection, long expectedDataRevision, String now) throws PersistenceException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ledger_meta SET updated_at = ?, data_revision = data_revision + 1 "
                        + "WHERE id = 1 AND data_revision = ?")) {
            statement.setString(1, now);
            statement.setLong(2, expectedDataRevision);
            if (statement.executeUpdate() != 1) {
                throw new PersistenceException("ledger metadata changed unexpectedly");
            }
        } catch (SQLException ex) {
            throw new PersistenceException("ledger metadata could not be updated", ex);
        }
    }

    public void insertOperation(Connection connection, LedgerOperation operation) throws PersistenceException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO processed_operation "
                        + "(idempotency_key, http_method, canonical_path, request_hash, response_status, response_json, "
                        + "profile_id, completed_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, operation.getIdempotencyKey());
            statement.setString(2, operation.getHttpMethod());
            statement.setString(3, operation.getCanonicalPath());
            statement.setString(4, operation.getRequestHash());
            statement.setInt(5, operation.getResponseStatus());
            statement.setString(6, operation.getResponseJson());
            statement.setString(7, operation.getProfileId());
            statement.setString(8, operation.getCompletedAt());
            statement.setString(9, operation.getExpiresAt());
            if (statement.executeUpdate() != 1) {
                throw new PersistenceException("ledger operation could not be recorded");
            }
        } catch (SQLException ex) {
            throw new PersistenceException("ledger operation could not be recorded", ex);
        }
    }

    public static final class SettingsState {
        private final SettingsRecord settings;
        private final long dataRevision;

        public SettingsState(SettingsRecord settings, long dataRevision) {
            this.settings = settings;
            this.dataRevision = dataRevision;
        }

        public SettingsRecord getSettings() { return settings; }
        public long getDataRevision() { return dataRevision; }
    }

    public static final class SettingsRecord {
        private final boolean notificationsEnabled;
        private final boolean autoBackupEnabled;
        private final int autoBackupIntervalDays;
        private final int autoBackupRetentionCount;
        private final String lastAutoBackupAt;
        private final String lastSettingsSection;
        private final String currencyCode;
        private final String currencySymbol;
        private final long safetyBufferMinor;
        private final boolean hideAllAmounts;
        private final String themeName;
        private final String customThemeCss;
        private final long revision;
        private final String updatedAt;

        public SettingsRecord(boolean notificationsEnabled, boolean autoBackupEnabled, int autoBackupIntervalDays,
                int autoBackupRetentionCount, String lastAutoBackupAt, String lastSettingsSection,
                String currencyCode, String currencySymbol, long safetyBufferMinor, boolean hideAllAmounts,
                String themeName, String customThemeCss, long revision, String updatedAt) {
            this.notificationsEnabled = notificationsEnabled;
            this.autoBackupEnabled = autoBackupEnabled;
            this.autoBackupIntervalDays = autoBackupIntervalDays;
            this.autoBackupRetentionCount = autoBackupRetentionCount;
            this.lastAutoBackupAt = lastAutoBackupAt;
            this.lastSettingsSection = lastSettingsSection;
            this.currencyCode = currencyCode;
            this.currencySymbol = currencySymbol;
            this.safetyBufferMinor = safetyBufferMinor;
            this.hideAllAmounts = hideAllAmounts;
            this.themeName = themeName;
            this.customThemeCss = customThemeCss;
            this.revision = revision;
            this.updatedAt = updatedAt;
        }

        public boolean isNotificationsEnabled() { return notificationsEnabled; }
        public boolean isAutoBackupEnabled() { return autoBackupEnabled; }
        public int getAutoBackupIntervalDays() { return autoBackupIntervalDays; }
        public int getAutoBackupRetentionCount() { return autoBackupRetentionCount; }
        public String getLastAutoBackupAt() { return lastAutoBackupAt; }
        public String getLastSettingsSection() { return lastSettingsSection; }
        public String getCurrencyCode() { return currencyCode; }
        public String getCurrencySymbol() { return currencySymbol; }
        public long getSafetyBufferMinor() { return safetyBufferMinor; }
        public boolean isHideAllAmounts() { return hideAllAmounts; }
        public String getThemeName() { return themeName; }
        public String getCustomThemeCss() { return customThemeCss; }
        public long getRevision() { return revision; }
        public String getUpdatedAt() { return updatedAt; }
    }

    public static final class LedgerOperation {
        private final String idempotencyKey;
        private final String httpMethod;
        private final String canonicalPath;
        private final String requestHash;
        private final int responseStatus;
        private final String responseJson;
        private final String profileId;
        private final String completedAt;
        private final String expiresAt;

        public LedgerOperation(String idempotencyKey, String httpMethod, String canonicalPath, String requestHash,
                int responseStatus, String responseJson, String profileId, String completedAt, String expiresAt) {
            this.idempotencyKey = idempotencyKey;
            this.httpMethod = httpMethod;
            this.canonicalPath = canonicalPath;
            this.requestHash = requestHash;
            this.responseStatus = responseStatus;
            this.responseJson = responseJson;
            this.profileId = profileId;
            this.completedAt = completedAt;
            this.expiresAt = expiresAt;
        }

        public String getIdempotencyKey() { return idempotencyKey; }
        public String getHttpMethod() { return httpMethod; }
        public String getCanonicalPath() { return canonicalPath; }
        public String getRequestHash() { return requestHash; }
        public int getResponseStatus() { return responseStatus; }
        public String getResponseJson() { return responseJson; }
        public String getProfileId() { return profileId; }
        public String getCompletedAt() { return completedAt; }
        public String getExpiresAt() { return expiresAt; }
    }
}
