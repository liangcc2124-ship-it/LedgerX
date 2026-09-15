package com.ledgerx.application.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.application.bootstrap.ApplicationBootstrap;
import com.ledgerx.application.bootstrap.ApplicationRuntime;
import com.ledgerx.persistence.SqliteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileApplicationServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T02:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NEW_ID = "17b65036-e5b1-4de9-b78d-068ae54ab047";

    @Test
    void createsReplaysSwitchesArchivesAndReopensWithSeparateLedgers(@TempDir Path temp) throws Exception {
        ProfileApplicationService service = ProfileApplicationService.open(temp, "test", CLOCK);
        String originalId = service.current().getActiveProfileId();
        ProfileMutation create = mutation("POST", "/api/v1/profiles", "a", "2f5aedce-f6cb-4b8e-bfac-5ca95808e060");

        ProfileApiResult created = service.create(NEW_ID, "  家庭账本  ", create);
        assertEquals(201, created.getStatus());
        assertEquals("\"0\"", created.getEtag());
        JsonNode createdBody = JSON.readTree(created.getResponseJson());
        assertEquals("家庭账本", createdBody.at("/data/profile/name").asText());
        assertEquals(1L, createdBody.at("/meta/catalogRevision").asLong());
        assertEquals(NEW_ID, service.current().getActiveProfileId());

        ProfileApiResult noOp = service.activate(NEW_ID, 0L,
                mutation("POST", "/api/v1/profiles/" + NEW_ID + "/activate", "d",
                        "3c642cf3-d303-463e-af8c-aa9a8967d16a"));
        assertEquals(200, noOp.getStatus());
        assertEquals(0L, JSON.readTree(noOp.getResponseJson()).at("/data/profile/revision").asLong());
        assertEquals(1L, JSON.readTree(noOp.getResponseJson()).at("/meta/catalogRevision").asLong());
        assertEquals(NEW_ID, service.current().getActiveProfileId());

        ProfileApiResult replay = service.create(NEW_ID, "家庭账本", create);
        assertEquals(created.getStatus(), replay.getStatus());
        assertEquals(created.getResponseJson(), replay.getResponseJson());
        assertEquals(created.getEtag(), replay.getEtag());

        ProfileApiResult operation = service.findOperation(create.getIdempotencyKey());
        JsonNode operationBody = JSON.readTree(operation.getResponseJson());
        assertEquals(201, operationBody.at("/data/responseStatus").asInt());
        assertEquals(NEW_ID, operationBody.at("/data/result/profile/id").asText());

        ProfileApiResult listed = service.list(false, 50, null);
        JsonNode listBody = JSON.readTree(listed.getResponseJson());
        assertEquals(2, listBody.at("/data/items").size());
        long originalRevision = profileRevision(listBody, originalId);
        assertEquals(1L, originalRevision);

        ProfileApiResult switched = service.activate(originalId, originalRevision,
                mutation("POST", "/api/v1/profiles/" + originalId + "/activate", "b",
                        "0407d5e5-2b13-49f3-9b1d-aec652b6be1f"));
        assertEquals(200, switched.getStatus());
        assertEquals(originalId, service.current().getActiveProfileId());
        assertEquals(2L, JSON.readTree(switched.getResponseJson()).at("/data/profile/revision").asLong());

        ProfileException staleArchive = assertThrows(ProfileException.class,
                () -> service.archive(NEW_ID, 0L, mutation("DELETE", "/api/v1/profiles/" + NEW_ID, "e",
                        "99327069-7f5f-44dd-a794-bb998728ad9a")));
        assertEquals("REVISION_CONFLICT", staleArchive.getCode());

        ProfileMutation archive = mutation("DELETE", "/api/v1/profiles/" + NEW_ID, "c",
                "8550678d-0f30-4df1-b7aa-04e3c668c5c1");
        ProfileApiResult archived = service.archive(NEW_ID, 1L,
                archive);
        assertEquals(200, archived.getStatus());
        assertEquals("ARCHIVED", JSON.readTree(archived.getResponseJson()).at("/data/profile/status").asText());
        assertTrue(Files.isRegularFile(temp.resolve("Profiles").resolve(NEW_ID).resolve("ledger.db")));
        assertEquals(archived.getResponseJson(), service.archive(NEW_ID, 1L, archive).getResponseJson());
        ProfileException archivedAgain = assertThrows(ProfileException.class,
                () -> service.archive(NEW_ID, 1L, mutation("DELETE", "/api/v1/profiles/" + NEW_ID, "d",
                        "5b013af5-875d-4d73-9704-d11b4cc9c82a")));
        assertEquals("REFERENCE_CONFLICT", archivedAgain.getCode());

        ProfileApplicationService reopened = ProfileApplicationService.open(temp, "test", CLOCK);
        assertEquals(originalId, reopened.current().getActiveProfileId());
        assertEquals(created.getResponseJson(), reopened.create(NEW_ID, "家庭账本", create).getResponseJson());
        assertEquals(201, JSON.readTree(reopened.findOperation(create.getIdempotencyKey()).getResponseJson())
                .at("/data/responseStatus").asInt());
        JsonNode reopenedList = JSON.readTree(reopened.list(true, 50, null).getResponseJson());
        assertEquals("ARCHIVED", profileStatus(reopenedList, NEW_ID));
        assertEquals(originalId, readLedgerProfileId(temp, originalId));
        assertEquals(NEW_ID, readLedgerProfileId(temp, NEW_ID));
        assertFalse(reopened.list(false, 50, null).getResponseJson().contains("\"status\":\"ARCHIVED\""));
    }

    @Test
    void conflictsAndUnsafeOrMissingTargetsLeaveActiveContextUnchanged(@TempDir Path temp) throws Exception {
        ProfileApplicationService service = ProfileApplicationService.open(temp, "test", CLOCK);
        String originalId = service.current().getActiveProfileId();
        ProfileMutation create = mutation("POST", "/api/v1/profiles", "a", "437c9b9e-3a75-4af7-9a63-3e34bc3c4560");
        service.create(NEW_ID, "家庭账本", create);

        ProfileException changedRequest = assertThrows(ProfileException.class,
                () -> service.create(NEW_ID, "另一名称", mutation("POST", "/api/v1/profiles", "d",
                        create.getIdempotencyKey())));
        assertEquals(409, changedRequest.getHttpStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", changedRequest.getCode());
        assertEquals(NEW_ID, service.current().getActiveProfileId());

        ProfileException changedPath = assertThrows(ProfileException.class,
                () -> service.activate(originalId, 1L, mutation("POST", "/api/v1/profiles/" + originalId + "/activate",
                        "a", create.getIdempotencyKey())));
        assertEquals("IDEMPOTENCY_CONFLICT", changedPath.getCode());

        insertLedgerOperation(temp, NEW_ID, "ca3f23e7-b67c-43e4-bd4e-7e1709852e9d");
        ProfileException crossScope = assertThrows(ProfileException.class,
                () -> service.create("b1165036-e5b1-4de9-b78d-068ae54ab047", "冲突",
                        mutation("POST", "/api/v1/profiles", "a", "ca3f23e7-b67c-43e4-bd4e-7e1709852e9d")));
        assertEquals("IDEMPOTENCY_CONFLICT", crossScope.getCode());

        ProfileException activeArchive = assertThrows(ProfileException.class,
                () -> service.archive(NEW_ID, 0L, mutation("DELETE", "/api/v1/profiles/" + NEW_ID, "e",
                        "d068f469-9362-44dd-b43d-00d4cc5afd27")));
        assertEquals(400, activeArchive.getHttpStatus());
        assertEquals("不能归档当前用户空间。", activeArchive.getFieldErrors().get("id"));

        Files.delete(temp.resolve("Profiles").resolve(originalId).resolve("ledger.db"));
        ProfileException missingLedger = assertThrows(ProfileException.class,
                () -> service.activate(originalId, 1L, mutation("POST", "/api/v1/profiles/" + originalId + "/activate",
                        "f", "6e8235ef-58ae-4e8b-8d04-2233c0fe3c8e")));
        assertEquals("REFERENCE_CONFLICT", missingLedger.getCode());
        assertEquals("TARGET_PROFILE_UNAVAILABLE", missingLedger.getDetails().get("reason"));
        assertEquals(NEW_ID, service.current().getActiveProfileId());

        try (Connection catalog = new SqliteDatabase(temp.resolve("profiles.db")).open();
                PreparedStatement update = catalog.prepareStatement(
                        "UPDATE profile SET relative_directory = ? WHERE id = ?")) {
            update.setString(1, "Profiles/../outside");
            update.setString(2, originalId);
            assertEquals(1, update.executeUpdate());
        }
        ProfileException escapedPath = assertThrows(ProfileException.class,
                () -> service.activate(originalId, 1L, mutation("POST", "/api/v1/profiles/" + originalId + "/activate",
                        "a", "de4fba2e-d3aa-46fd-a835-e3473d8f178a")));
        assertEquals("TARGET_PROFILE_UNAVAILABLE", escapedPath.getDetails().get("reason"));
        assertEquals(NEW_ID, service.current().getActiveProfileId());
    }

    @Test
    void recoveryModeExposesOnlyTheSafeCatalogList(@TempDir Path temp) throws Exception {
        ProfileApplicationService ready = ProfileApplicationService.open(temp, "test", CLOCK);
        String activeId = ready.current().getActiveProfileId();
        Files.delete(temp.resolve("Profiles").resolve(activeId).resolve("ledger.db"));

        ApplicationRuntime recovery = ApplicationBootstrap.runtimeFromDirectory(temp, "test", CLOCK);
        assertEquals("RECOVERY_REQUIRED", recovery.current().getState());
        JsonNode list = JSON.readTree(recovery.list(false, 50, null).getResponseJson());
        assertEquals(activeId, list.at("/data/activeProfileId").asText());
        assertTrue(list.at("/meta/dataRevision").isNull());
        ProfileException write = assertThrows(ProfileException.class,
                () -> recovery.create(NEW_ID, "拒绝", mutation("POST", "/api/v1/profiles", "a",
                        "a6522f54-1f0a-4860-9e1f-bd0f37bd1f4d")));
        assertEquals(423, write.getHttpStatus());
        assertEquals("RECOVERY_REQUIRED", write.getCode());
    }

    private static ProfileMutation mutation(String method, String path, String hashChar, String key) {
        return new ProfileMutation(key, method, path, hashChar.repeat(64));
    }

    private static long profileRevision(JsonNode list, String id) {
        for (JsonNode profile : list.at("/data/items")) {
            if (id.equals(profile.path("id").asText())) {
                return profile.path("revision").asLong();
            }
        }
        throw new AssertionError("profile not found: " + id);
    }

    private static String profileStatus(JsonNode list, String id) {
        for (JsonNode profile : list.at("/data/items")) {
            if (id.equals(profile.path("id").asText())) {
                return profile.path("status").asText();
            }
        }
        throw new AssertionError("profile not found: " + id);
    }

    private static String readLedgerProfileId(Path root, String id) throws Exception {
        try (Connection connection = new SqliteDatabase(root.resolve("Profiles").resolve(id).resolve("ledger.db")).open();
                PreparedStatement query = connection.prepareStatement("SELECT profile_id FROM ledger_meta WHERE id = 1");
                java.sql.ResultSet result = query.executeQuery()) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static void insertLedgerOperation(Path root, String profileId, String key) throws Exception {
        try (Connection connection = new SqliteDatabase(root.resolve("Profiles").resolve(profileId).resolve("ledger.db")).open();
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO processed_operation (idempotency_key, http_method, canonical_path, request_hash, "
                                + "response_status, response_json, profile_id, completed_at, expires_at) "
                                + "VALUES (?, 'POST', '/api/v1/records', ?, 201, '{}', ?, ?, ?)")) {
            insert.setString(1, key);
            insert.setString(2, "a".repeat(64));
            insert.setString(3, profileId);
            insert.setString(4, "2026-09-13T02:00:00Z");
            insert.setString(5, "2026-09-20T02:00:00Z");
            assertEquals(1, insert.executeUpdate());
        }
    }
}
