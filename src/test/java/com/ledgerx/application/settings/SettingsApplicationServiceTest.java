package com.ledgerx.application.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.application.bootstrap.ApplicationBootstrap;
import com.ledgerx.application.bootstrap.ApplicationRuntime;
import com.ledgerx.application.profile.ProfileMutation;
import com.ledgerx.persistence.SqliteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsApplicationServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T03:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void settingsAreIsolatedPersistedAndReplayTheOriginalResponse(@TempDir Path temp) throws Exception {
        ApplicationRuntime runtime = ApplicationBootstrap.runtimeFromDirectory(temp, "test", CLOCK);
        String firstProfileId = runtime.current().getActiveProfileId();
        SettingsApiResult initial = runtime.read();
        assertEquals("\"0\"", initial.getEtag());
        assertEquals("3000.00", JSON.readTree(initial.getResponseJson()).at("/data/safetyBuffer/amount").asText());

        String updateKey = "0e93c919-9e26-4ca0-9c07-89d886b53683";
        SettingsPatch patch = new SettingsPatch(true, false, 14, 20, "ACCOUNTS", 300001L, true, "GRAPHITE");
        SettingsApiResult updated = runtime.update(0L, patch, mutation(updateKey, "a"));
        JsonNode updatedJson = JSON.readTree(updated.getResponseJson());
        assertEquals("\"1\"", updated.getEtag());
        assertEquals(1L, updatedJson.at("/data/revision").asLong());
        assertEquals(1L, updatedJson.at("/meta/dataRevision").asLong());
        assertEquals("3000.01", updatedJson.at("/data/safetyBuffer/amount").asText());
        assertEquals("GRAPHITE", updatedJson.at("/data/themeName").asText());

        SettingsApiResult replay = runtime.update(0L, patch, mutation(updateKey, "a"));
        assertEquals(updated.getResponseJson(), replay.getResponseJson());
        assertEquals(updated.getEtag(), replay.getEtag());

        SettingsApiResult noOp = runtime.update(1L,
                new SettingsPatch(null, null, null, null, null, null, true, null), mutation(
                        "55d1e57f-d5c3-4fd2-a65b-0a5012c0d36c", "b"));
        assertEquals("\"1\"", noOp.getEtag());
        assertEquals(1L, JSON.readTree(noOp.getResponseJson()).at("/meta/dataRevision").asLong());

        Path firstLedger = temp.resolve("Profiles").resolve(firstProfileId).resolve("ledger.db");
        try (Connection connection = new SqliteDatabase(firstLedger).open();
                Statement statement = connection.createStatement();
                ResultSet setting = statement.executeQuery(
                        "SELECT notifications_enabled, auto_backup_enabled, auto_backup_interval_days, "
                                + "auto_backup_retention_count, last_settings_section, safety_buffer_minor, "
                                + "hide_all_amounts, theme_name, revision FROM ledger_setting WHERE id = 1")) {
            assertTrue(setting.next());
            assertEquals(1, setting.getInt("notifications_enabled"));
            assertEquals(0, setting.getInt("auto_backup_enabled"));
            assertEquals(14, setting.getInt("auto_backup_interval_days"));
            assertEquals(20, setting.getInt("auto_backup_retention_count"));
            assertEquals("ACCOUNTS", setting.getString("last_settings_section"));
            assertEquals(300001L, setting.getLong("safety_buffer_minor"));
            assertEquals(1, setting.getInt("hide_all_amounts"));
            assertEquals("GRAPHITE", setting.getString("theme_name"));
            assertEquals(1L, setting.getLong("revision"));
            assertFalse(setting.next());
            try (ResultSet operations = statement.executeQuery("SELECT COUNT(*) FROM processed_operation")) {
                assertTrue(operations.next());
                assertEquals(2, operations.getInt(1));
            }
        }

        String secondProfileId = UUID.randomUUID().toString();
        runtime.create(secondProfileId, "第二空间", profileMutation(
                "d52103c7-7a9d-4654-94a5-0a119faf579b", "/api/v1/profiles", "c"));
        SettingsApiResult secondProfile = runtime.read();
        assertEquals("\"0\"", secondProfile.getEtag());
        assertEquals(false, JSON.readTree(secondProfile.getResponseJson()).at("/data/notificationsEnabled").asBoolean());

        runtime.activate(firstProfileId, 1L, profileMutation(
                "245013fa-cb09-4baf-8dd2-7cfae56a8b4d", "/api/v1/profiles/" + firstProfileId + "/activate", "d"));
        assertEquals(updated.getResponseJson(), runtime.read().getResponseJson());

        ApplicationRuntime reopened = ApplicationBootstrap.runtimeFromDirectory(temp, "test", CLOCK);
        assertEquals(firstProfileId, reopened.current().getActiveProfileId());
        assertEquals(updated.getResponseJson(), reopened.read().getResponseJson());
    }

    @Test
    void invalidAndStaleSettingsWritesDoNotCreateOperations(@TempDir Path temp) throws Exception {
        ApplicationRuntime runtime = ApplicationBootstrap.runtimeFromDirectory(temp, "test", CLOCK);
        String profileId = runtime.current().getActiveProfileId();
        SettingsException invalid = assertThrows(SettingsException.class, () -> runtime.update(0L,
                new SettingsPatch(null, null, 0, null, null, null, null, null), mutation(
                        "ecd0081b-4c4e-4a09-84fe-98e28632af9f", "e")));
        assertEquals(400, invalid.getHttpStatus());

        runtime.update(0L, new SettingsPatch(null, null, null, null, null, null, true, null), mutation(
                "fc1b9ca3-d96f-44dc-945b-a51b9ab26e9f", "f"));
        SettingsException stale = assertThrows(SettingsException.class, () -> runtime.update(0L,
                new SettingsPatch(null, null, null, null, null, null, false, null), mutation(
                        "b1fe6728-d1f8-4115-9d75-086e4ce7fe9b", "0")));
        assertEquals(409, stale.getHttpStatus());
        assertEquals(1L, stale.getDetails().get("currentRevision"));

        Path ledger = temp.resolve("Profiles").resolve(profileId).resolve("ledger.db");
        try (Connection connection = new SqliteDatabase(ledger).open(); Statement statement = connection.createStatement();
                ResultSet operations = statement.executeQuery("SELECT COUNT(*) FROM processed_operation")) {
            assertTrue(operations.next());
            assertEquals(1, operations.getInt(1));
        }
    }

    private static SettingsMutation mutation(String key, String hashCharacter) {
        return new SettingsMutation(key, "PATCH", "/api/v1/settings", hashCharacter.repeat(64));
    }

    private static ProfileMutation profileMutation(String key, String path, String hashCharacter) {
        return new ProfileMutation(key, path.endsWith("/activate") ? "POST" : "POST", path, hashCharacter.repeat(64));
    }
}
