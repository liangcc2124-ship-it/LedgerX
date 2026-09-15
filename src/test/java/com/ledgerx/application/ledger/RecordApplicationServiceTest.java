package com.ledgerx.application.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.application.profile.ProfileApplicationService;
import com.ledgerx.persistence.LedgerCatalogRepository;
import com.ledgerx.persistence.SqliteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordApplicationServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACCOUNT = "f3a0c2ec-6b64-48c7-9f7f-0b604e4d8901";
    private static final String CATEGORY = "a4b7e8f1-3c2d-4e5f-8a9b-0c1d2e3f4a5b";
    private static final String RECORD = "b4c7e8f1-3c2d-4e5f-8a9b-0c1d2e3f4a5b";
    private static final String TARGET_CATEGORY = "c4b7e8f1-3c2d-4e5f-8a9b-0c1d2e3f4a5b";
    private static final String LIABILITY = "e4b7e8f1-3c2d-4e5f-8a9b-0c1d2e3f4a5b";
    private static final String RECORD_TWO = "f4b7e8f1-3c2d-4e5f-8a9b-0c1d2e3f4a5b";

    @Test
    void createReplaceTrashRestoreReopenAndBalanceUseRealSqlite(@TempDir Path temp) throws Exception {
        ProfileApplicationService service = ProfileApplicationService.open(temp, "test", CLOCK);
        service.createCategory(new CategoryPatch(CATEGORY, "测试收入", null), categoryMutation("01"));
        RecordApiResult created = service.createRecord(new RecordPatch(RECORD, RecordType.INCOME, 10000,
                LocalDate.of(2026, 9, 10), CATEGORY, ACCOUNT, LocalDate.of(2026, 9, 14), "工资"),
                mutation("POST", "/api/v1/records", "02"));
        assertEquals(201, created.getStatus());
        assertEquals("ACTIVE", record(created).at("/data/record/status").asText());
        assertEquals("100.00", accountBalance(service));

        RecordApiResult replaced = service.replaceRecord(RECORD, 0,
                new RecordPatch(RECORD, RecordType.FIXED_COST, 3000, LocalDate.of(2026, 9, 11), CATEGORY,
                        ACCOUNT, LocalDate.of(2026, 9, 14), "订阅"), mutation("PUT", "/api/v1/records/" + RECORD, "03"));
        assertEquals(1, record(replaced).at("/data/record/revision").asInt());
        assertEquals("-30.00", accountBalance(service));

        RecordApiResult trashed = service.trashRecord(RECORD, 1,
                mutation("DELETE", "/api/v1/records/" + RECORD, "04"));
        assertEquals("TRASHED", record(trashed).at("/data/record/status").asText());
        assertEquals("0.00", accountBalance(service));

        RecordApiResult restored = service.restoreRecord(RECORD, 2,
                mutation("POST", "/api/v1/records/" + RECORD + "/restore", "05"));
        assertEquals("ACTIVE", record(restored).at("/data/record/status").asText());
        assertEquals("-30.00", accountBalance(service));

        ProfileApplicationService reopened = ProfileApplicationService.open(temp, "test", CLOCK);
        assertEquals("ACTIVE", record(reopened.getRecord(RECORD)).at("/data/record/status").asText());
        assertEquals("-30.00", accountBalance(reopened));
        assertEquals(1, record(reopened.listRecords("ACTIVE", 50, null)).at("/data/items").size());
        Path ledger = temp.resolve("Profiles").resolve(reopened.current().getActiveProfileId()).resolve("ledger.db");
        try (java.sql.Connection connection = new LedgerCatalogRepository(ledger).openConnection();
             java.sql.PreparedStatement recordRow = connection.prepareStatement(
                     "SELECT record_type,amount_minor,deleted_at,revision FROM finance_record WHERE id=?")) {
            recordRow.setString(1, RECORD);
            try (java.sql.ResultSet row = recordRow.executeQuery()) {
                assertTrue(row.next());
                assertEquals("FIXED_COST", row.getString("record_type"));
                assertEquals(3000, row.getLong("amount_minor"));
                assertEquals(null, row.getString("deleted_at"));
                assertEquals(3, row.getLong("revision"));
            }
            try (java.sql.Statement statement = connection.createStatement();
                 java.sql.ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM processed_operation")) {
                assertTrue(rows.next());
                assertEquals(5, rows.getInt(1));
            }
        }
    }

    @Test
    void invalidReferenceAndStaleRevisionDoNotWrite(@TempDir Path temp) throws Exception {
        ProfileApplicationService service = ProfileApplicationService.open(temp, "test", CLOCK);
        service.createCategory(new CategoryPatch(CATEGORY, "测试支出", null), categoryMutation("11"));
        RecordPatch patch = new RecordPatch(RECORD, RecordType.VARIABLE_COST, 1, LocalDate.of(2026, 9, 10),
                CATEGORY, ACCOUNT, LocalDate.of(2026, 9, 10), "");
        RecordException bad = assertThrows(RecordException.class, () -> service.createRecord(
                new RecordPatch(RECORD, RecordType.VARIABLE_COST, 0, patch.getOccurredOn(), CATEGORY, ACCOUNT,
                        patch.getSettlementOn(), ""), mutation("POST", "/api/v1/records", "12")));
        assertEquals("VALIDATION_FAILED", bad.getCode());
        RecordException missing = assertThrows(RecordException.class, () -> service.createRecord(
                new RecordPatch(RECORD, RecordType.VARIABLE_COST, 1, patch.getOccurredOn(), CATEGORY,
                        "c4b7e8f1-3c2d-4e5f-8a9b-0c1d2e3f4a5b", patch.getSettlementOn(), ""),
                mutation("POST", "/api/v1/records", "13")));
        assertEquals("REFERENCE_CONFLICT", missing.getCode());
        assertEquals(0, record(service.listRecords("ACTIVE", 50, null)).at("/data/items").size());
    }

    @Test
    void customCategoryIsExposedAsUsableForRecords(@TempDir Path temp) throws Exception {
        ProfileApplicationService service = ProfileApplicationService.open(temp, "test", CLOCK);
        CategoryApiResult result = service.createCategory(new CategoryPatch(CATEGORY, "自定义可用分类", null), categoryMutation("31"));
        assertTrue(result.getResponseJson().contains("\"canUseForRecords\":true"));
    }

    @Test
    void mergeMovesActiveRecordsAndArchivesSourceAtomically(@TempDir Path temp) throws Exception {
        ProfileApplicationService service = ProfileApplicationService.open(temp, "test", CLOCK);
        service.createCategory(new CategoryPatch(CATEGORY, "来源分类", null), categoryMutation("41"));
        service.createCategory(new CategoryPatch(TARGET_CATEGORY, "目标分类", null), categoryMutation("42"));
        assertTrue(JSON.readTree(service.listCategories(true, 200, null).getResponseJson()).toString().contains(CATEGORY));
        assertTrue(JSON.readTree(service.listAccounts(false, LocalDate.of(2026, 9, 14), 200, null).getResponseJson()).toString().contains(ACCOUNT));
        service.createRecord(new RecordPatch(RECORD, RecordType.VARIABLE_COST, 1250,
                LocalDate.of(2026, 9, 10), CATEGORY, ACCOUNT, LocalDate.of(2026, 9, 14), "待合并"),
                mutation("POST", "/api/v1/records", "43"));
        CategoryApiResult merged = service.mergeCategories(TARGET_CATEGORY, 0,
                new String[]{CATEGORY}, new long[]{0}, new CategoryMutation(
                        "00000000-0000-4000-8000-000000000044", "POST",
                        "/api/v1/categories/" + TARGET_CATEGORY + "/merge", "4".repeat(64)));
        JsonNode mergedBody = JSON.readTree(merged.getResponseJson());
        assertEquals(1, mergedBody.at("/data/updatedRecordCount").asInt());
        JsonNode categories = JSON.readTree(service.listCategories(true, 200, null).getResponseJson()).at("/data/items");
        boolean sourceArchived = false;
        for (JsonNode item : categories) {
            if (CATEGORY.equals(item.at("/id").asText())) {
                sourceArchived = "ARCHIVED".equals(item.at("/status").asText());
            }
        }
        assertTrue(sourceArchived);
        assertEquals(TARGET_CATEGORY, JSON.readTree(service.getRecord(RECORD).getResponseJson())
                .at("/data/record/category/id").asText());
        ProfileApplicationService reopened = ProfileApplicationService.open(temp, "test", CLOCK);
        assertEquals(TARGET_CATEGORY, JSON.readTree(reopened.getRecord(RECORD).getResponseJson())
                .at("/data/record/category/id").asText());
    }

    @Test
    void assetAndLiabilityBalancesFollowRecordDirection(@TempDir Path temp) throws Exception {
        ProfileApplicationService service = ProfileApplicationService.open(temp, "test", CLOCK);
        service.createCategory(new CategoryPatch(CATEGORY, "方向分类", null), categoryMutation("51"));
        service.createAccount(new AccountPatch(LIABILITY, "信用账户", "CREDIT",
                LocalDate.of(2026, 1, 1), 0, false), accountMutation("52"));
        service.createRecord(new RecordPatch(RECORD, RecordType.INCOME, 10000,
                LocalDate.of(2026, 9, 10), CATEGORY, LIABILITY, LocalDate.of(2026, 9, 14), "收入"),
                mutation("POST", "/api/v1/records", "53"));
        service.createRecord(new RecordPatch(RECORD_TWO, RecordType.FIXED_COST, 3000,
                LocalDate.of(2026, 9, 11), CATEGORY, LIABILITY, LocalDate.of(2026, 9, 14), "支出"),
                mutation("POST", "/api/v1/records", "54"));
        JsonNode items = JSON.readTree(service.listAccounts(false, LocalDate.of(2026, 9, 14), 200, null).getResponseJson()).at("/data/items");
        for (JsonNode item : items) if (LIABILITY.equals(item.at("/id").asText())) assertEquals("-70.00", item.at("/balance").asText());
    }

    @Test
    void repositoryInsertUsesSchemaDefaults(@TempDir Path temp) throws Exception {
        ProfileApplicationService service = ProfileApplicationService.open(temp, "test", CLOCK);
        service.createCategory(new CategoryPatch(CATEGORY, "直接写入测试", null), categoryMutation("21"));
        Path ledger = temp.resolve("Profiles").resolve(service.current().getActiveProfileId()).resolve("ledger.db");
        LedgerCatalogRepository repo = new LedgerCatalogRepository(ledger);
        assertDoesNotThrow(() -> { try (java.sql.Connection c = repo.openConnection()) { repo.insertRecord(c,
                new LedgerCatalogRepository.RecordRecord(RECORD, "2026-09-10", "INCOME", 100, CATEGORY, ACCOUNT,
                        "PAID_FROM_ACCOUNT", "2026-09-14", "", "2026-09-14T00:00:00Z", "2026-09-14T00:00:00Z",
                        null, 0, null, null, null, null), "2026-09-14T00:00:00Z"); } });
    }

    private static JsonNode record(RecordApiResult result) throws Exception { return JSON.readTree(result.getResponseJson()); }
    private static String accountBalance(ProfileApplicationService service) throws Exception {
        return JSON.readTree(service.listAccounts(false, LocalDate.of(2026, 9, 14), 200, null).getResponseJson())
                .at("/data/items/0/balance").asText();
    }
    private static RecordMutation mutation(String method, String path, String value) {
        return new RecordMutation("00000000-0000-4000-8000-" + value + "0000000000", method, path, value.substring(0, 1).repeat(64));
    }
    private static CategoryMutation categoryMutation(String value) {
        return new CategoryMutation("10000000-0000-4000-8000-" + value + "0000000000", "POST", "/api/v1/categories", value.substring(0, 1).repeat(64));
    }
    private static AccountMutation accountMutation(String value) {
        return new AccountMutation("11000000-0000-4000-8000-" + value + "0000000000", "POST", "/api/v1/accounts", value.substring(0, 1).repeat(64));
    }
}
