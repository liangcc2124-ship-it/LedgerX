package com.ledgerx.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.application.ledger.AccountApiResult;
import com.ledgerx.application.ledger.AccountException;
import com.ledgerx.application.ledger.AccountMutation;
import com.ledgerx.application.ledger.AccountPatch;
import com.ledgerx.application.ledger.RecordException;
import com.ledgerx.application.ledger.RecordMutation;
import com.ledgerx.application.ledger.RecordPatch;
import com.ledgerx.application.ledger.RecordType;
import com.ledgerx.application.ledgerinitialization.LedgerInitializationApiResult;
import com.ledgerx.application.ledgerinitialization.LedgerInitializationDraft;
import com.ledgerx.application.ledgerinitialization.LedgerInitializationException;
import com.ledgerx.application.ledgerinitialization.LedgerInitializationMutation;
import com.ledgerx.application.profile.ProfileApplicationService;
import com.ledgerx.application.system.SystemStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerInitializationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_ACCOUNT = LedgerInitializationMigration.DEFAULT_ACCOUNT_ID;
    private static final String INCOME_CATEGORY = "d89db7bf-8d82-819e-bd38-e924e3c84c54";
    private static final String V001 = "/db/ledger/migration/V001__bootstrap.sql";
    private static final String V002 = "/db/ledger/migration/V002__ledger_settings.sql";
    private static final String V003 = "/db/ledger/migration/V003__ledger_core_facts.sql";
    private static final String V004 = "/db/ledger/migration/V004__core_catalog_seed.sql";
    private static final String V005 = "/db/ledger/migration/V005__metrics_formulas_dashboard.sql";

    @Test
    void freshLedgerWaitsForExplicitInitializationAndReplaysAtomically(@TempDir Path temp) throws Exception {
        ProfileApplicationService service = ProfileApplicationService.open(temp, "test", CLOCK);
        SystemStatus pending = service.current();
        assertEquals("READY", pending.getState());
        assertEquals("PENDING", pending.getSetupState());
        assertEquals(null, pending.getLedgerStartOn());
        assertTrue(pending.getCapabilities().contains("ledger.initialization.write"));
        assertFalse(pending.getCapabilities().contains("records.write"));

        AccountException blocked = assertThrows(AccountException.class, () -> service.createAccount(
                new AccountPatch(UUID.randomUUID().toString(), "Before setup", "BANK",
                        LocalDate.of(2025, 1, 1), 0, true), accountMutation("POST", "/api/v1/accounts")));
        assertEquals("LEDGER_SETUP_REQUIRED", blocked.getCode());
        JsonNode preview = JSON.readTree(service.readInitialization().getResponseJson());
        assertEquals("PENDING", preview.at("/data/setupState").asText());
        assertEquals("2026-09-14", preview.at("/data/today").asText());

        LedgerInitializationException futureDate = assertThrows(LedgerInitializationException.class,
                () -> service.initializeLedger(freshDraft(LocalDate.of(2026, 9, 15), "0.00", "现金储备"),
                        new LedgerInitializationMutation(UUID.randomUUID().toString(), "f".repeat(64))));
        assertEquals("VALIDATION_FAILED", futureDate.getCode());
        assertTrue(futureDate.getFieldErrors().containsKey("ledgerStartOn"));

        String key = UUID.randomUUID().toString();
        LedgerInitializationMutation mutation = new LedgerInitializationMutation(key, "a".repeat(64));
        LedgerInitializationDraft draft = freshDraft(LocalDate.of(2025, 1, 1), "0.00", "现金储备");
        LedgerInitializationApiResult completed = service.initializeLedger(draft, mutation);
        assertEquals(200, completed.getStatus());
        assertEquals("COMPLETED", JSON.readTree(completed.getResponseJson()).at("/data/setupState").asText());
        assertEquals(1, JSON.readTree(completed.getResponseJson()).at("/meta/dataRevision").asInt());

        LedgerInitializationApiResult replay = service.initializeLedger(draft, mutation);
        assertEquals(completed.getResponseJson(), replay.getResponseJson());
        assertEquals(1L, service.current().getDataRevision());
        assertEquals("COMPLETED", service.current().getSetupState());
        assertEquals("2025-01-01", service.current().getLedgerStartOn());
        assertTrue(service.current().getCapabilities().contains("records.write"));

        LedgerInitializationException changedBody = assertThrows(LedgerInitializationException.class,
                () -> service.initializeLedger(freshDraft(LocalDate.of(2025, 1, 1), "1.00", "现金储备"),
                        new LedgerInitializationMutation(key, "c".repeat(64))));
        assertEquals("IDEMPOTENCY_CONFLICT", changedBody.getCode());
        LedgerInitializationException secondAttempt = assertThrows(LedgerInitializationException.class,
                () -> service.initializeLedger(draft, new LedgerInitializationMutation(UUID.randomUUID().toString(), "b".repeat(64))));
        assertEquals("ALREADY_INITIALIZED", secondAttempt.getCode());

        Path ledger = temp.resolve("Profiles").resolve(pending.getActiveProfileId()).resolve("ledger.db");
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals(1, scalar(connection, "SELECT revision FROM ledger_setting WHERE id=1"));
            assertEquals(1, scalar(connection, "SELECT data_revision FROM ledger_meta WHERE id=1"));
            assertEquals(1, scalar(connection, "SELECT revision FROM financial_account WHERE id='" + DEFAULT_ACCOUNT + "'"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM processed_operation"));
            assertEquals("2025-01-01", scalarText(connection,
                    "SELECT ledger_start_on FROM ledger_setting WHERE id=1"));
        }
    }

    @Test
    void concurrentSameKeyInitializationCommitsAndReplaysExactlyOnce(@TempDir Path temp) throws Exception {
        ProfileApplicationService service = ProfileApplicationService.open(temp, "test", CLOCK);
        LedgerInitializationDraft draft = freshDraft(LocalDate.of(2025, 1, 1), "0.00", "现金储备");
        LedgerInitializationMutation mutation = new LedgerInitializationMutation(UUID.randomUUID().toString(), "d".repeat(64));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<LedgerInitializationApiResult> request = () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("concurrent request was not released");
                return service.initializeLedger(draft, mutation);
            };
            Future<LedgerInitializationApiResult> first = executor.submit(request);
            Future<LedgerInitializationApiResult> second = executor.submit(request);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            LedgerInitializationApiResult firstResult = first.get(10, TimeUnit.SECONDS);
            LedgerInitializationApiResult secondResult = second.get(10, TimeUnit.SECONDS);
            assertEquals(200, firstResult.getStatus());
            assertEquals(firstResult.getResponseJson(), secondResult.getResponseJson());
            assertEquals(1L, service.current().getDataRevision());
            Path ledger = temp.resolve("Profiles").resolve(service.current().getActiveProfileId()).resolve("ledger.db");
            try (Connection connection = new SqliteDatabase(ledger).open()) {
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM processed_operation"));
                assertEquals(1, scalar(connection, "SELECT revision FROM ledger_setting WHERE id=1"));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void datesRespectLedgerFloorAccountOpeningAndTrashedSettlements(@TempDir Path temp) throws Exception {
        ProfileApplicationService service = readyService(temp);
        String accountId = UUID.randomUUID().toString();
        LocalDate openingOn = LocalDate.of(2025, 1, 5);
        service.createAccount(new AccountPatch(accountId, "测试账户", "BANK", openingOn, 0, true),
                accountMutation("POST", "/api/v1/accounts"));

        AccountException earlyAccount = assertThrows(AccountException.class, () -> service.createAccount(
                new AccountPatch(UUID.randomUUID().toString(), "早于起始日", "BANK",
                        LocalDate.of(2024, 12, 31), 0, true), accountMutation("POST", "/api/v1/accounts")));
        assertEquals("openingOn", earlyAccount.getFieldErrors().keySet().iterator().next());

        String recordId = UUID.randomUUID().toString();
        RecordException earlyOccurred = assertThrows(RecordException.class, () -> service.createRecord(
                record(recordId, accountId, LocalDate.of(2024, 12, 31), LocalDate.of(2025, 1, 6)),
                recordMutation("POST", "/api/v1/records")));
        assertEquals("occurredOn", earlyOccurred.getFieldErrors().keySet().iterator().next());

        RecordException accountNotOpen = assertThrows(RecordException.class, () -> service.createRecord(
                record(recordId, accountId, LocalDate.of(2025, 1, 2), LocalDate.of(2025, 1, 4)),
                recordMutation("POST", "/api/v1/records")));
        assertEquals("ACCOUNT_NOT_OPEN_ON_SETTLEMENT_DATE", accountNotOpen.getCode());
        assertTrue(accountNotOpen.getFieldErrors().containsKey("settlement.settlementOn"));

        AccountApiResult createdAccount = service.createAccount(new AccountPatch(UUID.randomUUID().toString(),
                "有效账户", "BANK", LocalDate.of(2025, 1, 1), 0, true),
                accountMutation("POST", "/api/v1/accounts"));
        assertEquals(201, createdAccount.getStatus());

        service.createRecord(record(recordId, accountId, LocalDate.of(2025, 1, 2), LocalDate.of(2025, 1, 6)),
                recordMutation("POST", "/api/v1/records"));
        service.trashRecord(recordId, 0, recordMutation("DELETE", "/api/v1/records/" + recordId));
        AccountException trashStillBlocks = assertThrows(AccountException.class, () -> service.replaceAccount(
                accountId, 0, new AccountPatch(accountId, "测试账户", "BANK", LocalDate.of(2025, 1, 7), 0, true),
                accountMutation("PUT", "/api/v1/accounts/" + accountId)));
        assertEquals(409, trashStillBlocks.getHttpStatus());
        assertEquals("2025-01-06", trashStillBlocks.getDetails().get("earliestSettlementOn"));
    }

    @Test
    void reviewInitializationRequiresAndAppliesExplicitHistoricalAccountCorrections(@TempDir Path temp)
            throws Exception {
        ProfileApplicationService service = ProfileApplicationService.open(temp, "test", CLOCK);
        String profileId = service.current().getActiveProfileId();
        String historicalAccountId = "a4b7e8f1-3c2d-4e5f-8a9b-0c1d2e3f4a5b";
        Path ledger = temp.resolve("Profiles").resolve(profileId).resolve("ledger.db");
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE financial_account SET name='历史现金',opening_on='2025-03-01',"
                            + "opening_balance_minor=12500,revision=1 WHERE id=?")) {
                update.setString(1, DEFAULT_ACCOUNT);
                update.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO financial_account(id,name,kind,balance_side,opening_on,opening_balance_minor,"
                            + "include_in_available_cash,is_system,archived_at,created_at,updated_at,revision) "
                            + "VALUES(?,'历史银行卡','BANK','ASSET','2025-03-01',50000,1,0,NULL,?,?,0)")) {
                insert.setString(1, historicalAccountId);
                insert.setString(2, CLOCK.instant().toString());
                insert.setString(3, CLOCK.instant().toString());
                insert.executeUpdate();
            }
            insertReviewRecord(connection, RECORD_ID, DEFAULT_ACCOUNT, "2025-01-01", "2025-02-01", null);
            insertReviewRecord(connection, "f9185e69-9ae9-46ab-94bb-38df00a473d1", historicalAccountId,
                    "2025-01-02", "2025-02-10", "2025-02-11T00:00:00Z");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE ledger_setting SET setup_state='REVIEW_REQUIRED',"
                        + "ledger_start_on=NULL,setup_completed_at=NULL WHERE id=1");
            }
        }

        JsonNode preview = JSON.readTree(service.readInitialization().getResponseJson());
        assertEquals("REVIEW_REQUIRED", preview.at("/data/setupState").asText());
        assertEquals("2025-01-01", preview.at("/data/suggestedLedgerStartOn").asText());
        assertEquals("2025-02-01", preview.at("/data/defaultAccount/earliestSettlementOn").asText());
        assertEquals(1, preview.at("/data/accountsNeedingOpeningDateReview").size());
        assertEquals(historicalAccountId,
                preview.at("/data/accountsNeedingOpeningDateReview/0/accountId").asText());

        LedgerInitializationDraft corrections = new LedgerInitializationDraft(LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 2, 1), "125.00", null, true,
                Collections.singletonMap(historicalAccountId, LocalDate.of(2025, 2, 10)));
        LedgerInitializationException unconfirmed = assertThrows(LedgerInitializationException.class,
                () -> service.initializeLedger(new LedgerInitializationDraft(corrections.getLedgerStartOn(),
                        corrections.getDefaultAccountOpeningOn(), corrections.getOpeningBalance(), null, false,
                        corrections.getAccountOpeningDates()),
                        new LedgerInitializationMutation(UUID.randomUUID().toString(), "a".repeat(64))));
        assertEquals("REVIEW_CONFIRMATION_REQUIRED", unconfirmed.getCode());

        LedgerInitializationMutation retryableMutation = new LedgerInitializationMutation(
                UUID.randomUUID().toString(), "b".repeat(64));
        try (Connection connection = new SqliteDatabase(ledger).open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TRIGGER reject_review_date BEFORE UPDATE OF opening_on ON financial_account "
                    + "WHEN OLD.id='" + historicalAccountId + "' BEGIN SELECT RAISE(ABORT,'injected failure'); END");
        }
        LedgerInitializationException failedTransaction = assertThrows(LedgerInitializationException.class,
                () -> service.initializeLedger(corrections, retryableMutation));
        assertEquals(503, failedTransaction.getHttpStatus());
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals("REVIEW_REQUIRED", scalarText(connection, "SELECT setup_state FROM ledger_setting WHERE id=1"));
            assertEquals(0, scalar(connection, "SELECT revision FROM ledger_setting WHERE id=1"));
            assertEquals(0, scalar(connection, "SELECT data_revision FROM ledger_meta WHERE id=1"));
            assertEquals("2025-03-01", scalarText(connection,
                    "SELECT opening_on FROM financial_account WHERE id='" + DEFAULT_ACCOUNT + "'"));
            assertEquals(1, scalar(connection, "SELECT revision FROM financial_account WHERE id='" + DEFAULT_ACCOUNT + "'"));
            assertEquals("2025-03-01", scalarText(connection,
                    "SELECT opening_on FROM financial_account WHERE id='" + historicalAccountId + "'"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM processed_operation"));
            try (Statement statement = connection.createStatement()) { statement.executeUpdate("DROP TRIGGER reject_review_date"); }
        }

        LedgerInitializationApiResult completed = service.initializeLedger(corrections, retryableMutation);
        assertEquals(200, completed.getStatus());
        assertEquals("COMPLETED", service.current().getSetupState());
        JsonNode result = JSON.readTree(completed.getResponseJson());
        assertEquals("历史现金", result.at("/data/defaultAccount/name").asText());
        assertEquals("2025-02-01", result.at("/data/defaultAccount/openingOn").asText());
        assertEquals("125.00", result.at("/data/defaultAccount/openingBalance").asText());
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals(1, scalar(connection, "SELECT data_revision FROM ledger_meta WHERE id=1"));
            assertEquals(1, scalar(connection, "SELECT revision FROM ledger_setting WHERE id=1"));
            assertEquals(2, scalar(connection, "SELECT revision FROM financial_account WHERE id='" + DEFAULT_ACCOUNT + "'"));
            assertEquals(1, scalar(connection, "SELECT revision FROM financial_account WHERE id='" + historicalAccountId + "'"));
            assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM finance_record"));
            assertEquals("2025-02-11T00:00:00Z", scalarText(connection,
                    "SELECT deleted_at FROM finance_record WHERE id='f9185e69-9ae9-46ab-94bb-38df00a473d1'"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM processed_operation"));
        }
    }

    @Test
    void v005EmptyAndHistoricalLedgersAreClassifiedWithoutChangingBusinessRevisions(@TempDir Path temp)
            throws Exception {
        String emptyProfile = UUID.randomUUID().toString();
        Path empty = createV5Ledger(temp.resolve("empty"), emptyProfile);
        BootstrapSnapshot emptySnapshot = new LedgerBootstrap(CLOCK).open(empty, emptyProfile);
        assertEquals("PENDING", emptySnapshot.getSetupState());
        assertEquals(null, emptySnapshot.getLedgerStartOn());

        String historyProfile = UUID.randomUUID().toString();
        Path history = createV5Ledger(temp.resolve("history"), historyProfile);
        String openingOn;
        try (Connection connection = new SqliteDatabase(history).open()) {
            openingOn = scalarText(connection, "SELECT opening_on FROM financial_account WHERE id='" + DEFAULT_ACCOUNT + "'");
            try (PreparedStatement meta = connection.prepareStatement(
                    "UPDATE ledger_meta SET data_revision=9 WHERE id=1")) { meta.executeUpdate(); }
            try (PreparedStatement setting = connection.prepareStatement(
                    "UPDATE ledger_setting SET revision=4 WHERE id=1")) { setting.executeUpdate(); }
            insertHistoricalRecord(connection, "2020-02-29", openingOn, "2020-03-01T00:00:00Z", 2);
        }
        BootstrapSnapshot historySnapshot = new LedgerBootstrap(CLOCK).open(history, historyProfile);
        assertEquals("COMPLETED", historySnapshot.getSetupState());
        assertEquals("2020-02-29", historySnapshot.getLedgerStartOn());
        try (Connection connection = new SqliteDatabase(history).open()) {
            assertEquals(9, scalar(connection, "SELECT data_revision FROM ledger_meta WHERE id=1"));
            assertEquals(4, scalar(connection, "SELECT revision FROM ledger_setting WHERE id=1"));
            assertEquals(2, scalar(connection, "SELECT revision FROM finance_record WHERE id='" + RECORD_ID + "'"));
            assertEquals(CLOCK.instant().toString(), scalarText(connection,
                    "SELECT setup_completed_at FROM ledger_setting WHERE id=1"));
            assertEquals(6, scalar(connection, "SELECT COUNT(*) FROM schema_history"));
        }

        String reviewProfile = UUID.randomUUID().toString();
        Path review = createV5Ledger(temp.resolve("review"), reviewProfile);
        try (Connection connection = new SqliteDatabase(review).open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE financial_account SET name='用户改过的名称' WHERE id='" + DEFAULT_ACCOUNT + "'");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO financial_account(id,name,kind,balance_side,opening_on,opening_balance_minor,"
                            + "include_in_available_cash,is_system,archived_at,created_at,updated_at,revision) "
                            + "VALUES('d5f286d8-1eb9-4c3d-8b92-8f4448f8a8d2','旧银行卡','BANK','ASSET',"
                            + "'2022-05-01',45000,1,0,NULL,?,?,0)")) {
                insert.setString(1, CLOCK.instant().toString());
                insert.setString(2, CLOCK.instant().toString());
                insert.executeUpdate();
            }
        }
        BootstrapSnapshot reviewSnapshot = new LedgerBootstrap(CLOCK).open(review, reviewProfile);
        assertEquals("REVIEW_REQUIRED", reviewSnapshot.getSetupState());
        assertEquals(null, reviewSnapshot.getLedgerStartOn());
        try (Connection connection = new SqliteDatabase(review).open()) {
            assertEquals("用户改过的名称", scalarText(connection,
                    "SELECT name FROM financial_account WHERE id='" + DEFAULT_ACCOUNT + "'"));
            assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM financial_account"));
            assertEquals(45000, scalar(connection,
                    "SELECT opening_balance_minor FROM financial_account WHERE id='d5f286d8-1eb9-4c3d-8b92-8f4448f8a8d2'"));
        }

        String conflictProfile = UUID.randomUUID().toString();
        Path conflict = createV5Ledger(temp.resolve("history-conflict"), conflictProfile);
        String archivedAccountId = "79fd31c6-7c3e-4be0-ae4c-6fd2c685e540";
        try (Connection connection = new SqliteDatabase(conflict).open()) {
            insertHistoricalAccount(connection, archivedAccountId, "已归档旧账户", "2025-03-01",
                    "2025-04-01T00:00:00Z");
            insertReviewRecord(connection, "e489f06f-f69b-4d3a-89a1-4fc1ba7e3c42", archivedAccountId,
                    "2025-01-01", "2025-02-01", "2025-02-02T00:00:00Z");
        }
        BootstrapSnapshot conflictSnapshot = new LedgerBootstrap(CLOCK).open(conflict, conflictProfile);
        assertEquals("REVIEW_REQUIRED", conflictSnapshot.getSetupState());
        assertEquals(null, conflictSnapshot.getLedgerStartOn());
        try (Connection connection = new SqliteDatabase(conflict).open()) {
            assertEquals("2025-03-01", scalarText(connection,
                    "SELECT opening_on FROM financial_account WHERE id='" + archivedAccountId + "'"));
            assertEquals("2025-02-02T00:00:00Z", scalarText(connection,
                    "SELECT deleted_at FROM finance_record WHERE id='e489f06f-f69b-4d3a-89a1-4fc1ba7e3c42'"));
        }
    }

    @Test
    void unknownModesAndOrphanedAccountsAbortV006WithoutPartialSchema(@TempDir Path temp) throws Exception {
        Path unknownMode = createV5Ledger(temp.resolve("unknown-mode"), UUID.randomUUID().toString());
        try (Connection connection = new SqliteDatabase(unknownMode).open()) {
            insertReviewRecord(connection, RECORD_ID, DEFAULT_ACCOUNT, "2025-01-01", "2025-01-02", null);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE finance_record SET settlement_mode='UNRECOGNIZED' WHERE id='" + RECORD_ID + "'");
            }
        }
        assertThrows(PersistenceException.class, () -> new LedgerBootstrap(CLOCK).open(unknownMode, UUID.randomUUID().toString()));
        assertV006RolledBack(unknownMode);

        Path orphan = createV5Ledger(temp.resolve("orphan-account"), UUID.randomUUID().toString());
        try (Connection connection = new SqliteDatabase(orphan).open()) {
            try (Statement statement = connection.createStatement()) { statement.execute("PRAGMA foreign_keys=OFF"); }
            insertReviewRecord(connection, RECORD_ID, "00000000-0000-4000-8000-000000000001",
                    "2025-01-01", "2025-01-02", null);
            try (Statement statement = connection.createStatement()) { statement.execute("PRAGMA foreign_keys=ON"); }
        }
        assertThrows(PersistenceException.class, () -> new LedgerBootstrap(CLOCK).open(orphan, UUID.randomUUID().toString()));
        assertV006RolledBack(orphan);
    }

    @Test
    void malformedHistoricalDateRollsBackV006DdlHookAndHistory(@TempDir Path temp) throws Exception {
        String profileId = UUID.randomUUID().toString();
        Path ledger = createV5Ledger(temp, profileId);
        try (Connection connection = new SqliteDatabase(ledger).open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE financial_account SET opening_on='not-a-date' WHERE id='" + DEFAULT_ACCOUNT + "'");
        }
        assertThrows(PersistenceException.class, () -> new LedgerBootstrap(CLOCK).open(ledger, profileId));
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals(5, scalar(connection, "SELECT COUNT(*) FROM schema_history"));
            assertFalse(columnExists(connection, "ledger_setting", "setup_state"));
            assertEquals("not-a-date", scalarText(connection,
                    "SELECT opening_on FROM financial_account WHERE id='" + DEFAULT_ACCOUNT + "'"));
        }
    }

    private static ProfileApplicationService readyService(Path temp) throws Exception {
        ProfileApplicationService service = ProfileApplicationService.open(temp, "test", CLOCK);
        service.initializeLedger(freshDraft(LocalDate.of(2025, 1, 1), "0.00", "现金储备"),
                new LedgerInitializationMutation(UUID.randomUUID().toString(), "c".repeat(64)));
        return service;
    }

    private static LedgerInitializationDraft freshDraft(LocalDate start, String openingBalance, String accountName) {
        return new LedgerInitializationDraft(start, start, openingBalance, accountName, false, Collections.emptyMap());
    }

    private static AccountMutation accountMutation(String method, String path) {
        return new AccountMutation(UUID.randomUUID().toString(), method, path, "d".repeat(64));
    }

    private static RecordMutation recordMutation(String method, String path) {
        return new RecordMutation(UUID.randomUUID().toString(), method, path, "e".repeat(64));
    }

    private static RecordPatch record(String id, String accountId, LocalDate occurredOn, LocalDate settlementOn) {
        return new RecordPatch(id, RecordType.INCOME, 1000, occurredOn, INCOME_CATEGORY, accountId,
                settlementOn, "initialization test");
    }

    private static Path createV5Ledger(Path root, String profileId) throws Exception {
        Files.createDirectories(root);
        Path ledger = root.resolve("ledger.db");
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals(5, new MigrationRunner(Arrays.asList(V001, V002, V003, V004, V005),
                    "schema_history", CLOCK).migrate(connection));
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO ledger_meta(id,profile_id,created_at,updated_at,data_revision) VALUES(1,?,?,?,0)")) {
                insert.setString(1, profileId);
                insert.setString(2, CLOCK.instant().toString());
                insert.setString(3, CLOCK.instant().toString());
                insert.executeUpdate();
            }
        }
        return ledger;
    }

    private static void insertHistoricalRecord(Connection connection, String occurredOn, String accountOpeningOn,
            String deletedAt, long revision) throws Exception {
        String settlement = LocalDate.parse(accountOpeningOn).plusDays(1).toString();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO finance_record(id,occurred_on,record_type,amount_minor,currency_code,category_id,account_id,"
                        + "settlement_mode,settlement_on,income_source,is_self_generated_income,is_non_essential,note,"
                        + "created_at,updated_at,deleted_at,revision) VALUES(?,?,'INCOME',1000,'CNY',?,?,"
                        + "'PAID_FROM_ACCOUNT',?,NULL,0,0,'old',?,?,?,?)")) {
            insert.setString(1, RECORD_ID);
            insert.setString(2, occurredOn);
            insert.setString(3, INCOME_CATEGORY);
            insert.setString(4, DEFAULT_ACCOUNT);
            insert.setString(5, settlement);
            insert.setString(6, CLOCK.instant().toString());
            insert.setString(7, CLOCK.instant().toString());
            insert.setString(8, deletedAt);
            insert.setLong(9, revision);
            insert.executeUpdate();
        }
    }

    private static void insertReviewRecord(Connection connection, String recordId, String accountId,
            String occurredOn, String settlementOn, String deletedAt) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO finance_record(id,occurred_on,record_type,amount_minor,currency_code,category_id,account_id,"
                        + "settlement_mode,settlement_on,income_source,is_self_generated_income,is_non_essential,note,"
                        + "created_at,updated_at,deleted_at,revision) VALUES(?,?,'INCOME',1000,'CNY',?,?,'PAID_FROM_ACCOUNT',?"
                        + ",NULL,0,0,'review history',?,?,?,0)")) {
            insert.setString(1, recordId);
            insert.setString(2, occurredOn);
            insert.setString(3, INCOME_CATEGORY);
            insert.setString(4, accountId);
            insert.setString(5, settlementOn);
            insert.setString(6, CLOCK.instant().toString());
            insert.setString(7, CLOCK.instant().toString());
            insert.setString(8, deletedAt);
            insert.executeUpdate();
        }
    }

    private static void insertHistoricalAccount(Connection connection, String accountId, String name,
            String openingOn, String archivedAt) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO financial_account(id,name,kind,balance_side,opening_on,opening_balance_minor,"
                        + "include_in_available_cash,is_system,archived_at,created_at,updated_at,revision) "
                        + "VALUES(?,?,'BANK','ASSET',?,50000,1,0,?,?,?,2)")) {
            insert.setString(1, accountId);
            insert.setString(2, name);
            insert.setString(3, openingOn);
            insert.setString(4, archivedAt);
            insert.setString(5, CLOCK.instant().toString());
            insert.setString(6, CLOCK.instant().toString());
            insert.executeUpdate();
        }
    }

    private static int scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static String scalarText(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet columns = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (columns.next()) if (column.equals(columns.getString("name"))) return true;
            return false;
        }
    }

    private static void assertV006RolledBack(Path ledger) throws Exception {
        try (Connection connection = new SqliteDatabase(ledger).open()) {
            assertEquals(5, scalar(connection, "SELECT COUNT(*) FROM schema_history"));
            assertFalse(columnExists(connection, "ledger_setting", "setup_state"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM finance_record"));
        }
    }

    private static final String RECORD_ID = "8abeb4a0-bf47-4a7c-b7e4-a2f3898b40ad";
}
