package com.ledgerx.application.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.application.profile.ProfileApiResult;
import com.ledgerx.application.profile.ProfileApplicationService;
import com.ledgerx.application.profile.ProfileMutation;
import com.ledgerx.application.settings.SettingsApiResult;
import com.ledgerx.application.settings.SettingsMutation;
import com.ledgerx.application.settings.SettingsPatch;
import com.ledgerx.persistence.SqliteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static com.ledgerx.testsupport.LedgerTestSupport.initializeLedger;
import static com.ledgerx.testsupport.LedgerTestSupport.openReadyLedger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupApplicationServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T04:30:00Z"), ZoneOffset.UTC);

    @Test
    void createsValidatedPackageAndDurablyReplaysOneId(@TempDir Path temp) throws Exception {
        ProfileApplicationService service = openReadyLedger(temp, "test-version", CLOCK);
        String profileId = service.current().getActiveProfileId();
        long beforeRevision = service.current().getDataRevision();
        String key = "9fc1df09-f1d3-4c9b-97be-f592f65d0c29";

        BackupApiResult created = service.createBackup(key);
        BackupApiResult replay = service.createBackup(key);
        assertEquals(201, created.getStatus());
        assertEquals(created.getResponseJson(), replay.getResponseJson());
        assertEquals(beforeRevision, service.current().getDataRevision());

        Path packageFile = temp.resolve("Backups").resolve(profileId).resolve(key + ".ledgerx-backup");
        assertTrue(Files.isRegularFile(packageFile));
        assertEquals(1L, Files.list(packageFile.getParent()).filter(path -> path.toString().endsWith(".ledgerx-backup")).count());
        try (ZipFile zip = new ZipFile(packageFile.toFile())) {
            List<String> names = new ArrayList<>();
            zip.stream().forEach(entry -> names.add(entry.getName()));
            assertEquals(List.of("manifest.json", "ledger.db"), names);
            JsonNode manifest = JSON.readTree(zip.getInputStream(zip.getEntry("manifest.json")));
            assertEquals(3, manifest.path("formatVersion").asInt());
            assertEquals(profileId, manifest.path("profileId").asText());
            assertEquals(key, manifest.path("backupId").asText());
            assertFalse(manifest.path("encrypted").asBoolean(true));
            assertEquals(64, manifest.at("/ledger/sha256").asText().length());
        }

        JsonNode verified = JSON.readTree(service.verifyBackup(key).getResponseJson());
        assertEquals("VALID", verified.at("/data/verification/status").asText());
        assertEquals(key, service.openBackupDownload(key).getId());
        JsonNode listed = JSON.readTree(service.listBackups(25, null).getResponseJson());
        assertEquals(1, listed.at("/data/items").size());
        assertEquals(key, listed.at("/data/items/0/id").asText());
        assertEquals("NOT_VERIFIED", listed.at("/data/items/0/integrityStatus").asText());

        try (ZipFile zip = new ZipFile(packageFile.toFile())) {
            Path extracted = temp.resolve("reopened.db");
            Files.copy(zip.getInputStream(zip.getEntry("ledger.db")), extracted);
            try (Connection connection = new SqliteDatabase(extracted).open();
                    Statement statement = connection.createStatement();
                    ResultSet integrity = statement.executeQuery("PRAGMA integrity_check")) {
                assertTrue(integrity.next());
                assertEquals("ok", integrity.getString(1));
            }
        }
    }

    @Test
    void concurrentRetriesPublishExactlyOnePackage(@TempDir Path temp) throws Exception {
        ProfileApplicationService service = openReadyLedger(temp, "test-version", CLOCK);
        String profileId = service.current().getActiveProfileId();
        String key = "49f69c30-2485-4633-a806-9f577f3248d2";
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<BackupApiResult> first = executor.submit(() -> { start.await(); return service.createBackup(key); });
            Future<BackupApiResult> second = executor.submit(() -> { start.await(); return service.createBackup(key); });
            start.countDown();
            assertEquals(first.get().getResponseJson(), second.get().getResponseJson());
        } finally {
            executor.shutdownNow();
        }
        Path directory = temp.resolve("Backups").resolve(profileId);
        assertEquals(1L, Files.list(directory).filter(path -> path.toString().endsWith(".ledgerx-backup")).count());
        assertEquals("VALID", JSON.readTree(service.verifyBackup(key).getResponseJson())
                .at("/data/verification/status").asText());
    }

    @Test
    void rejectsBadHashUnknownFormatAndCrossProfileLookup(@TempDir Path temp) throws Exception {
        ProfileApplicationService service = openReadyLedger(temp, "test-version", CLOCK);
        String originalProfile = service.current().getActiveProfileId();
        String badHash = "41f69330-2485-4633-a806-9f577f3248d2";
        service.createBackup(badHash);
        Path badHashFile = temp.resolve("Backups").resolve(originalProfile).resolve(badHash + ".ledgerx-backup");
        rewriteManifest(badHashFile, manifest -> manifest.with("ledger").put("sha256", "0".repeat(64)));
        BackupException hashError = assertThrows(BackupException.class, () -> service.verifyBackup(badHash));
        assertEquals(422, hashError.getHttpStatus());
        assertEquals("BACKUP_INVALID", hashError.getCode());

        String unknown = "32a67a9d-d3d8-4903-821f-332f101a1fb3";
        service.createBackup(unknown);
        Path unknownFile = temp.resolve("Backups").resolve(originalProfile).resolve(unknown + ".ledgerx-backup");
        rewriteManifest(unknownFile, manifest -> manifest.put("formatVersion", 99));
        assertEquals("BACKUP_INVALID", assertThrows(BackupException.class,
                () -> service.verifyBackup(unknown)).getCode());

        String truncated = "c0ed1e6f-1d76-4bbf-8c92-b82c92b64cb3";
        service.createBackup(truncated);
        Path truncatedFile = temp.resolve("Backups").resolve(originalProfile).resolve(truncated + ".ledgerx-backup");
        byte[] prefix = java.util.Arrays.copyOf(Files.readAllBytes(truncatedFile), 16);
        Files.write(truncatedFile, prefix);
        assertEquals("BACKUP_INVALID", assertThrows(BackupException.class,
                () -> service.verifyBackup(truncated)).getCode());

        String second = "17b65036-e5b1-4de9-b78d-068ae54ab047";
        service.create(second, "另一空间", new ProfileMutation("2f5aedce-f6cb-4b8e-bfac-5ca95808e060",
                "POST", "/api/v1/profiles", "a".repeat(64)));
        initializeLedger(service, "59f65a34-3732-42f3-a746-0b7d11d29714", "b");
        assertEquals(0, JSON.readTree(service.listBackups(25, null).getResponseJson()).at("/data/items").size());
        BackupException hidden = assertThrows(BackupException.class, () -> service.verifyBackup(badHash));
        assertEquals(404, hidden.getHttpStatus());

        ProfileApiResult profiles = service.list(false, 25, null);
        JsonNode items = JSON.readTree(profiles.getResponseJson()).at("/data/items");
        long originalRevision = -1;
        for (JsonNode item : items) if (originalProfile.equals(item.path("id").asText())) originalRevision = item.path("revision").asLong();
        service.activate(originalProfile, originalRevision, new ProfileMutation(
                "82d3928d-0ac7-4a95-943d-397118e6cb31", "POST",
                "/api/v1/profiles/" + originalProfile + "/activate", "c".repeat(64)));
        assertEquals("BACKUP_INVALID", assertThrows(BackupException.class,
                () -> service.verifyBackup(badHash)).getCode());
    }

    @Test
    void onlineSnapshotRemainsValidWhileApplicationWritesContinue(@TempDir Path temp) throws Exception {
        ProfileApplicationService service = openReadyLedger(temp, "test-version", CLOCK);
        String profileId = service.current().getActiveProfileId();
        Path ledger = temp.resolve("Profiles").resolve(profileId).resolve("ledger.db");
        try (Connection connection = new SqliteDatabase(ledger).open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE backup_concurrency_payload (id INTEGER PRIMARY KEY, payload BLOB NOT NULL)");
            statement.execute("INSERT INTO backup_concurrency_payload(payload) VALUES(zeroblob(67108864))");
        }

        AtomicBoolean backupRunning = new AtomicBoolean();
        AtomicBoolean writeOverlapped = new AtomicBoolean();
        CountDownLatch backupStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        String backupId = "ad9575ee-c353-499b-9aa7-50afd8d7f19e";
        try {
            Future<BackupApiResult> backup = executor.submit(() -> {
                backupRunning.set(true);
                backupStarted.countDown();
                try { return service.createBackup(backupId); }
                finally { backupRunning.set(false); }
            });
            Future<Long> writes = executor.submit(() -> {
                backupStarted.await();
                long revision = JSON.readTree(service.read().getResponseJson()).at("/data/revision").asLong();
                for (int index = 0; index < 12; index++) {
                    if (backupRunning.get()) writeOverlapped.set(true);
                    boolean hidden = index % 2 == 0;
                    SettingsApiResult result = service.update(revision,
                            new SettingsPatch(null, null, null, null, null, null, hidden, null),
                            new SettingsMutation(UUID.randomUUID().toString(), "PATCH", "/api/v1/settings",
                                    String.format("%064x", index + 1)));
                    revision = JSON.readTree(result.getResponseJson()).at("/data/revision").asLong();
                    if (backupRunning.get()) writeOverlapped.set(true);
                }
                return revision;
            });
            assertEquals(201, backup.get().getStatus());
            assertTrue(writes.get() >= 1L);
        } finally {
            executor.shutdownNow();
        }
        assertTrue(writeOverlapped.get(), "at least one real application write should overlap online backup");
        assertEquals("VALID", JSON.readTree(service.verifyBackup(backupId).getResponseJson())
                .at("/data/verification/status").asText());
    }

    private static void rewriteManifest(Path packageFile, ManifestMutation mutation) throws Exception {
        byte[] manifest;
        byte[] ledger;
        try (ZipFile zip = new ZipFile(packageFile.toFile())) {
            manifest = zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes();
            ledger = zip.getInputStream(zip.getEntry("ledger.db")).readAllBytes();
        }
        JsonNode root = JSON.readTree(manifest);
        mutation.apply((com.fasterxml.jackson.databind.node.ObjectNode) root);
        Path replacement = packageFile.resolveSibling(packageFile.getFileName() + ".replacement");
        try (OutputStream output = Files.newOutputStream(replacement); ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(JSON.writeValueAsBytes(root));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ledger.db"));
            zip.write(ledger);
            zip.closeEntry();
        }
        Files.move(replacement, packageFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private interface ManifestMutation {
        void apply(com.fasterxml.jackson.databind.node.ObjectNode manifest);
    }
}
