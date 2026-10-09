package com.ledgerx.application.profile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.application.system.SystemStatus;
import com.ledgerx.application.system.SystemStatusProvider;
import com.ledgerx.application.backup.BackupApi;
import com.ledgerx.application.backup.BackupApiResult;
import com.ledgerx.application.backup.BackupDownload;
import com.ledgerx.application.backup.BackupException;
import com.ledgerx.application.ledgerinitialization.LedgerInitializationApi;
import com.ledgerx.application.ledgerinitialization.LedgerInitializationApiResult;
import com.ledgerx.application.ledgerinitialization.LedgerInitializationDraft;
import com.ledgerx.application.ledgerinitialization.LedgerInitializationException;
import com.ledgerx.application.ledgerinitialization.LedgerInitializationMutation;
import com.ledgerx.application.settings.SettingsApi;
import com.ledgerx.application.settings.SettingsApiResult;
import com.ledgerx.application.settings.SettingsException;
import com.ledgerx.application.settings.SettingsMutation;
import com.ledgerx.application.settings.SettingsPatch;
import com.ledgerx.application.ledger.CategoryApi;
import com.ledgerx.application.ledger.CategoryApiResult;
import com.ledgerx.application.ledger.CategoryException;
import com.ledgerx.application.ledger.CategoryMutation;
import com.ledgerx.application.ledger.CategoryPatch;
import com.ledgerx.application.ledger.AccountApi;
import com.ledgerx.application.ledger.AccountApiResult;
import com.ledgerx.application.ledger.AccountException;
import com.ledgerx.application.ledger.AccountMutation;
import com.ledgerx.application.ledger.AccountPatch;
import com.ledgerx.application.ledger.RecordApi;
import com.ledgerx.application.ledger.RecordApiResult;
import com.ledgerx.application.ledger.RecordException;
import com.ledgerx.application.ledger.RecordMutation;
import com.ledgerx.application.ledger.RecordPatch;
import com.ledgerx.application.ledger.RecordQuery;
import com.ledgerx.application.ledger.RecordType;
import com.ledgerx.application.metrics.DashboardApi;
import com.ledgerx.application.metrics.DashboardApiResult;
import com.ledgerx.application.metrics.DashboardException;
import com.ledgerx.application.metrics.DashboardLayoutDraft;
import com.ledgerx.application.metrics.DashboardMutation;
import com.ledgerx.application.metrics.DashboardPeriod;
import com.ledgerx.application.metrics.FormulaEvaluationResult;
import com.ledgerx.application.metrics.FormulaEvaluationContext;
import com.ledgerx.application.metrics.FormulaEvaluator;
import com.ledgerx.application.metrics.FormulaDraftRequest;
import com.ledgerx.application.metrics.FormulaToken;
import com.ledgerx.application.metrics.FormulaNode;
import com.ledgerx.application.metrics.FormulaReference;
import com.ledgerx.application.metrics.FormulaValidationResult;
import com.ledgerx.application.metrics.FormulaValidator;
import com.ledgerx.application.metrics.FormulaStorageMapper;
import com.ledgerx.application.metrics.CanonicalFormulaTokens;
import com.ledgerx.application.metrics.MetricApiResult;
import com.ledgerx.application.metrics.MetricDataStatus;
import com.ledgerx.application.metrics.MetricDraft;
import com.ledgerx.application.metrics.MetricException;
import com.ledgerx.application.metrics.MetricGranularity;
import com.ledgerx.application.metrics.MetricMutation;
import com.ledgerx.application.metrics.MetricListQuery;
import com.ledgerx.application.metrics.MetricsApi;
import com.ledgerx.application.metrics.PeriodResolver;
import com.ledgerx.application.metrics.PeriodStatus;
import com.ledgerx.application.metrics.ResolvedPeriod;
import com.ledgerx.application.metrics.SystemMetricCalculator;
import com.ledgerx.persistence.BootstrapSnapshot;
import com.ledgerx.persistence.LedgerSettingsRepository;
import com.ledgerx.persistence.LedgerSettingsRepository.LedgerOperation;
import com.ledgerx.persistence.LedgerSettingsRepository.SettingsRecord;
import com.ledgerx.persistence.LedgerSettingsRepository.SettingsState;
import com.ledgerx.persistence.LedgerCatalogRepository;
import com.ledgerx.persistence.LedgerCatalogRepository.CategoryRecord;
import com.ledgerx.persistence.LedgerCatalogRepository.AccountRecord;
import com.ledgerx.persistence.LedgerCatalogRepository.RecordRecord;
import com.ledgerx.persistence.LedgerMetricsRepository;
import com.ledgerx.persistence.LedgerMetricsRepository.LayoutItemRecord;
import com.ledgerx.persistence.LedgerMetricsRepository.LayoutRecord;
import com.ledgerx.persistence.LedgerMetricsRepository.MetricDefinitionRecord;
import com.ledgerx.persistence.LedgerMetricsRepository.MetricFactsRecord;
import com.ledgerx.persistence.LedgerMetricsRepository.VisibilityRecord;
import com.ledgerx.persistence.LedgerMetricsRepository.FormulaVersionRecord;
import com.ledgerx.persistence.LedgerBootstrap;
import com.ledgerx.persistence.LedgerInitializationMigration;
import com.ledgerx.persistence.PersistenceException;
import com.ledgerx.persistence.ProfileBootstrap;
import com.ledgerx.persistence.ProfileCatalogRepository;
import com.ledgerx.persistence.ProfileCatalogRepository.CatalogOperation;
import com.ledgerx.persistence.ProfileCatalogRepository.CatalogProfile;
import com.ledgerx.persistence.ProfileCatalogRepository.CatalogState;
import com.ledgerx.persistence.SqliteDatabase;
import com.ledgerx.persistence.TransactionRunner;
import com.ledgerx.persistence.BackupStore;
import com.ledgerx.persistence.BackupStore.BackupArtifact;
import com.ledgerx.persistence.BackupStore.BackupInvalidException;
import com.ledgerx.persistence.BackupStore.BackupNotFoundException;
import com.ledgerx.http.FormulaHttpMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The sole owner of the active profile identity.  Catalog writes hold one
 * process-local exclusive gate so the system-status projection and profile
 * operations cannot observe different active profile snapshots.
 */
public final class ProfileApplicationService implements ProfileApi, SettingsApi, CategoryApi, AccountApi, RecordApi,
        MetricsApi, DashboardApi, SystemStatusProvider, LedgerInitializationApi, BackupApi {
    private static final Duration OPERATION_RETENTION = Duration.ofDays(7);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String API_VERSION = "1.0";
    private static final int BACKUP_FORMAT_VERSION = 3;

    private final String applicationVersion;
    private final Clock clock;
    private final ProfileBootstrap profileBootstrap;
    private final LedgerBootstrap ledgerBootstrap;
    private final ProfileCatalogRepository catalog;
    private final BackupStore backupStore;
    private final LedgerSettingsRepository settings = new LedgerSettingsRepository();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TransactionRunner transactions = new TransactionRunner();
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock(true);
    private volatile ActiveProfileContext activeContext;

    private ProfileApplicationService(String applicationVersion, Path dataRoot, Clock clock,
            BootstrapSnapshot initialSnapshot) {
        this.applicationVersion = applicationVersion;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.profileBootstrap = new ProfileBootstrap(dataRoot, this.clock);
        this.ledgerBootstrap = new LedgerBootstrap(this.clock);
        this.catalog = new ProfileCatalogRepository(profileBootstrap.getCatalogFile());
        this.backupStore = new BackupStore(dataRoot, applicationVersion, this.clock);
        this.activeContext = initialSnapshot == null ? null : new ActiveProfileContext(initialSnapshot,
                profileBootstrap.getDataRoot().resolve("Profiles").resolve(initialSnapshot.getProfileId())
                        .resolve("ledger.db").toAbsolutePath().normalize());
    }

    public static ProfileApplicationService open(Path dataRoot, String applicationVersion, Clock clock)
            throws PersistenceException {
        if (applicationVersion == null || applicationVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("applicationVersion is required");
        }
        Clock actualClock = clock == null ? Clock.systemDefaultZone() : clock;
        ProfileBootstrap bootstrap = new ProfileBootstrap(dataRoot, actualClock);
        BootstrapSnapshot snapshot = bootstrap.open();
        return new ProfileApplicationService(applicationVersion, dataRoot, actualClock, snapshot);
    }

    /**
     * Creates the narrow read-only profile catalog view used by recovery mode.
     * It deliberately does not bootstrap, migrate, create, or open a ledger.
     */
    public static ProfileApplicationService openRecoveryCatalog(Path dataRoot, String applicationVersion, Clock clock)
            throws PersistenceException {
        if (applicationVersion == null || applicationVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("applicationVersion is required");
        }
        Clock actualClock = clock == null ? Clock.systemDefaultZone() : clock;
        ProfileBootstrap bootstrap = new ProfileBootstrap(dataRoot, actualClock);
        Path catalogFile = bootstrap.getCatalogFile();
        if (!Files.isDirectory(bootstrap.getDataRoot(), LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(bootstrap.getDataRoot())
                || !Files.isRegularFile(catalogFile, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(catalogFile)) {
            throw new PersistenceException("recovery catalog is unavailable");
        }
        return new ProfileApplicationService(applicationVersion, dataRoot, actualClock, null);
    }

    @Override
    public SystemStatus current() {
        gate.readLock().lock();
        try {
            if (activeContext == null) {
                throw new IllegalStateException("recovery catalog has no active application context");
            }
            BootstrapSnapshot snapshot = activeContext.snapshot;
            boolean setupComplete = "COMPLETED".equals(snapshot.getSetupState());
            List<String> capabilities = setupComplete
                    ? Arrays.asList("system.status", "profiles.read", "profiles.write", "settings.read", "settings.write",
                            "categories.read", "categories.write", "accounts.read", "accounts.write",
                            "records.read", "records.write", "metrics.read", "metrics.write", "formulas.validate",
                            "formulas.write", "dashboard.read", "dashboard.layout.write", "backups.create",
                            "backups.list", "backups.verify", "backups.download")
                    : Arrays.asList("system.status", "profiles.read", "profiles.write", "ledger.initialization.read",
                            "ledger.initialization.write", "operations.read");
            return new SystemStatus(
                    API_VERSION,
                    applicationVersion,
                    snapshot.getSchemaVersion(),
                    BACKUP_FORMAT_VERSION,
                    snapshot.getProfileId(),
                    "READY",
                    capabilities,
                    snapshot.getDataRevision(), snapshot.getSetupState(), snapshot.getLedgerStartOn());
        } finally {
            gate.readLock().unlock();
        }
    }

    @Override
    public BackupApiResult createBackup(String idempotencyKey) throws BackupException {
        validateBackupUuid(idempotencyKey, "Idempotency-Key");
        BackupContext context = captureBackupContext();
        try {
            BackupArtifact artifact = backupStore.create(context.ledgerFile, context.profileId, idempotencyKey);
            return backupResult(201, artifact, artifact.getDataRevision(),
                    "/api/v1/backups/" + idempotencyKey + "/download");
        } catch (BackupInvalidException ex) {
            throw backupInvalid();
        } catch (BackupNotFoundException ex) {
            throw backupNotFound();
        } catch (PersistenceException ex) {
            throw backupUnavailable();
        }
    }

    @Override
    public BackupApiResult listBackups(int limit, String cursor) throws BackupException {
        if (limit < 1 || limit > 100) {
            throw backupValidation("limit", "必须在 1 到 100 之间。");
        }
        BackupContext context = captureBackupContext();
        BackupCursor parsed = cursor == null ? null : BackupCursor.parse(cursor, context.profileId, objectMapper);
        try {
            List<BackupArtifact> all = backupStore.list(context.profileId);
            int start = 0;
            if (parsed != null) {
                start = -1;
                for (int index = 0; index < all.size(); index++) {
                    BackupArtifact item = all.get(index);
                    if (parsed.id.equals(item.getId()) && parsed.createdAt.equals(nullToEmpty(item.getCreatedAt()))) {
                        start = index + 1;
                        break;
                    }
                }
                if (start < 0) throw backupValidation("cursor", "游标已失效或不属于当前用户空间。");
            }
            int end = Math.min(all.size(), start + limit);
            List<Map<String, Object>> items = new ArrayList<>();
            for (int index = start; index < end; index++) items.add(backupMap(all.get(index)));
            boolean hasMore = end < all.size();
            String next = hasMore && end > start ? BackupCursor.encode(context.profileId, all.get(end - 1), objectMapper) : null;
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("nextCursor", next);
            page.put("hasMore", hasMore);
            page.put("limit", limit);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", items);
            data.put("page", page);
            return backupJsonResult(200, data, context.snapshot.getDataRevision(), null);
        } catch (BackupException ex) {
            throw ex;
        } catch (PersistenceException ex) {
            throw backupUnavailable();
        }
    }

    @Override
    public BackupApiResult verifyBackup(String id) throws BackupException {
        if (!isBackupUuid(id)) throw backupNotFound();
        BackupContext context = captureBackupContext();
        try {
            BackupArtifact artifact = backupStore.verify(context.profileId, id);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("backup", backupMap(artifact));
            Map<String, Object> verification = new LinkedHashMap<>();
            verification.put("status", "VALID");
            verification.put("verifiedAt", Instant.now(clock).toString());
            data.put("verification", verification);
            return backupJsonResult(200, data, artifact.getDataRevision(), null);
        } catch (BackupNotFoundException ex) {
            throw backupNotFound();
        } catch (BackupInvalidException ex) {
            throw backupInvalid();
        } catch (PersistenceException ex) {
            throw backupUnavailable();
        }
    }

    @Override
    public BackupDownload openBackupDownload(String id) throws BackupException {
        if (!isBackupUuid(id)) throw backupNotFound();
        BackupContext context = captureBackupContext();
        try {
            Path file = backupStore.resolveDownload(context.profileId, id);
            return new BackupDownload(id, file, Files.size(file));
        } catch (BackupNotFoundException ex) {
            throw backupNotFound();
        } catch (PersistenceException | IOException ex) {
            throw backupUnavailable();
        }
    }

    private BackupContext captureBackupContext() throws BackupException {
        gate.readLock().lock();
        try {
            if (activeContext == null) throw new BackupException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能操作备份。");
            if (!"COMPLETED".equals(activeContext.snapshot.getSetupState())) {
                throw new BackupException(409, "LEDGER_SETUP_REQUIRED", "请先完成账本初始化。");
            }
            return new BackupContext(activeContext.snapshot, activeContext.ledgerFile);
        } finally {
            gate.readLock().unlock();
        }
    }

    private BackupApiResult backupResult(int status, BackupArtifact artifact, long revision, String location)
            throws BackupException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("backup", backupMap(artifact));
        return backupJsonResult(status, data, revision, location);
    }

    private BackupApiResult backupJsonResult(int status, Map<String, Object> data, long revision, String location)
            throws BackupException {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", data);
        response.put("meta", Collections.singletonMap("dataRevision", revision));
        try {
            return new BackupApiResult(status, objectMapper.writeValueAsString(response), location);
        } catch (JsonProcessingException ex) {
            throw new BackupException(500, "INTERNAL_ERROR", "本地服务发生内部错误。");
        }
    }

    private static Map<String, Object> backupMap(BackupArtifact artifact) {
        boolean invalid = "INVALID".equals(artifact.getIntegrityStatus());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", artifact.getId());
        map.put("profileId", artifact.getProfileId());
        map.put("createdAt", artifact.getCreatedAt());
        map.put("formatVersion", invalid ? null : BackupStore.FORMAT_VERSION);
        map.put("schemaVersion", artifact.getSchemaVersion() == 0 ? null : artifact.getSchemaVersion());
        map.put("applicationVersion", artifact.getApplicationVersion());
        map.put("dataRevision", invalid ? null : artifact.getDataRevision());
        map.put("counts", artifact.getCounts());
        map.put("sizeBytes", artifact.getPackageSizeBytes());
        map.put("integrityStatus", artifact.getIntegrityStatus());
        map.put("encrypted", false);
        map.put("fileName", artifact.getId() + ".ledgerx-backup");
        return map;
    }

    private static void validateBackupUuid(String value, String field) throws BackupException {
        if (!isBackupUuid(value)) throw backupValidation(field, "必须提供小写 UUID v4。");
    }

    private static boolean isBackupUuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) return false;
        try { UUID uuid = UUID.fromString(value); return uuid.version() == 4 && uuid.variant() == 2 && uuid.toString().equals(value); }
        catch (IllegalArgumentException ex) { return false; }
    }

    private static BackupException backupValidation(String field, String message) {
        return new BackupException(400, "VALIDATION_FAILED", "请求参数无效。",
                Collections.singletonMap(field, message), Collections.emptyMap());
    }

    private static BackupException backupNotFound() {
        return new BackupException(404, "NOT_FOUND", "备份不存在。");
    }

    private static BackupException backupInvalid() {
        return new BackupException(422, "BACKUP_INVALID", "备份文件无效或已损坏。");
    }

    private static BackupException backupUnavailable() {
        return new BackupException(503, "SERVICE_UNAVAILABLE", "本机备份暂时不可用。");
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    @Override
    public LedgerInitializationApiResult readInitialization() throws LedgerInitializationException {
        gate.readLock().lock();
        try {
            if (activeContext == null) {
                throw new LedgerInitializationException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能初始化账本。");
            }
            try (Connection connection = new SqliteDatabase(activeContext.ledgerFile).open()) {
                Map<String, Object> data = initializationSummary(connection);
                return initializationResult(200, data, repositoryDataRevision(connection));
            } catch (IOException | SQLException | PersistenceException ex) {
                throw initializationUnavailable();
            }
        } finally {
            gate.readLock().unlock();
        }
    }

    @Override
    public LedgerInitializationApiResult initializeLedger(LedgerInitializationDraft draft,
            LedgerInitializationMutation mutation) throws LedgerInitializationException {
        validateInitializationRequest(draft, mutation);
        gate.writeLock().lock();
        try {
            if (activeContext == null) {
                throw new LedgerInitializationException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能初始化账本。");
            }
            try (Connection connection = new SqliteDatabase(activeContext.ledgerFile).open()) {
                SetupCommit commit = transactions.write(connection, tx -> {
                    Instant now = Instant.now(clock);
                    String timestamp = now.toString();
                    settings.removeExpiredOperations(tx, now);
                    LedgerOperation previous = settings.findOperation(tx, mutation.getIdempotencyKey(), now);
                    if (previous != null) {
                        if (!"POST".equals(previous.getHttpMethod())
                                || !"/api/v1/ledger-initialization".equals(previous.getCanonicalPath())
                                || !mutation.getRequestHash().equals(previous.getRequestHash())) {
                            throw new LedgerInitializationException(409, "IDEMPOTENCY_CONFLICT",
                                    "幂等键已用于不同请求。");
                        }
                        return new SetupCommit(new LedgerInitializationApiResult(previous.getResponseStatus(),
                                previous.getResponseJson()), -1L, null);
                    }

                    String state = readSetupState(tx);
                    if ("COMPLETED".equals(state)) {
                        throw new LedgerInitializationException(409, "ALREADY_INITIALIZED", "账本已经完成初始化。");
                    }
                    if (!"PENDING".equals(state) && !"REVIEW_REQUIRED".equals(state)) {
                        throw initializationUnavailable();
                    }
                    SetupFacts facts = readSetupFacts(tx);
                    validateSetupDraft(tx, draft, state, facts);

                    long dataRevision = repositoryDataRevision(tx);
                    updateDefaultAccount(tx, facts.defaultAccount, draft, timestamp);
                    updateReviewedAccountDates(tx, draft, facts, timestamp);
                    try (PreparedStatement update = tx.prepareStatement(
                            "UPDATE ledger_setting SET setup_state='COMPLETED',ledger_start_on=?,"
                                    + "setup_completed_at=?,revision=revision+1,updated_at=? WHERE id=1 ")) {
                        update.setString(1, draft.getLedgerStartOn().toString());
                        update.setString(2, timestamp);
                        update.setString(3, timestamp);
                        if (update.executeUpdate() != 1) throw new SQLException("ledger settings row is missing");
                    }
                    settings.advanceLedgerMeta(tx, dataRevision, timestamp);
                    long nextRevision = dataRevision + 1;
                    Map<String, Object> responseData = new LinkedHashMap<>();
                    responseData.put("setupState", "COMPLETED");
                    responseData.put("ledgerStartOn", draft.getLedgerStartOn().toString());
                    responseData.put("defaultAccount", defaultAccountSetupMap(tx));
                    LedgerInitializationApiResult result = initializationResult(200, responseData, nextRevision);
                    settings.insertOperation(tx, new LedgerOperation(mutation.getIdempotencyKey(), "POST",
                            "/api/v1/ledger-initialization", mutation.getRequestHash(), result.getStatus(),
                            result.getResponseJson(), activeContext.snapshot.getProfileId(), timestamp,
                            now.plus(OPERATION_RETENTION).toString()));
                    return new SetupCommit(result, nextRevision, draft.getLedgerStartOn().toString());
                });
                if (commit.dataRevision >= 0) {
                    BootstrapSnapshot previous = activeContext.snapshot;
                    activeContext = new ActiveProfileContext(new BootstrapSnapshot(previous.getProfileId(),
                            previous.getSchemaVersion(), commit.dataRevision, "COMPLETED", commit.ledgerStartOn),
                            activeContext.ledgerFile);
                }
                return commit.result;
            } catch (LedgerInitializationException ex) {
                throw ex;
            } catch (PersistenceException | SQLException ex) {
                throw initializationUnavailable();
            } catch (Exception ex) {
                throw initializationUnavailable();
            }
        } finally {
            gate.writeLock().unlock();
        }
    }

    private Map<String, Object> initializationSummary(Connection connection)
            throws SQLException, PersistenceException {
        SetupFacts facts = readSetupFacts(connection);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("setupState", facts.setupState);
        data.put("today", businessToday().toString());
        data.put("ledgerStartOn", facts.ledgerStartOn);
        data.put("suggestedLedgerStartOn", facts.suggestedStart == null ? null : facts.suggestedStart.toString());
        Map<String, Object> defaultAccount = new LinkedHashMap<>();
        defaultAccount.put("id", facts.defaultAccount.id);
        defaultAccount.put("name", facts.defaultAccount.name);
        defaultAccount.put("openingOn", facts.defaultAccount.openingOn.toString());
        defaultAccount.put("openingBalance", formatMoney(facts.defaultAccount.openingBalanceMinor));
        defaultAccount.put("earliestSettlementOn", facts.defaultAccount.earliestSettlementOn);
        defaultAccount.put("status", facts.defaultAccount.archivedAt == null ? "ACTIVE" : "ARCHIVED");
        data.put("defaultAccount", defaultAccount);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (SetupAccount account : facts.accounts.values()) {
            if (!LedgerInitializationMigration.DEFAULT_ACCOUNT_ID.equals(account.id)
                    && account.earliestSettlementOn != null
                    && account.openingOn.toString().compareTo(account.earliestSettlementOn) > 0) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("accountId", account.id);
                item.put("name", account.name);
                item.put("openingOn", account.openingOn.toString());
                item.put("earliestSettlementOn", account.earliestSettlementOn);
                item.put("status", account.archivedAt == null ? "ACTIVE" : "ARCHIVED");
                conflicts.add(item);
            }
        }
        data.put("accountsNeedingOpeningDateReview", conflicts);
        return data;
    }

    private SetupFacts readSetupFacts(Connection connection) throws SQLException, PersistenceException {
        String state;
        String startOn;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT setup_state,ledger_start_on FROM ledger_setting WHERE id=1");
                ResultSet row = query.executeQuery()) {
            if (!row.next()) throw new PersistenceException("ledger settings are missing");
            state = row.getString("setup_state");
            startOn = row.getString("ledger_start_on");
        }
        Map<String, SetupAccount> accounts = new LinkedHashMap<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT a.id,a.name,a.opening_on,a.opening_balance_minor,a.is_system,a.archived_at,a.revision,"
                        + "(SELECT MIN(r.settlement_on) FROM finance_record r WHERE r.account_id=a.id "
                        + "AND r.settlement_mode='PAID_FROM_ACCOUNT') AS earliest_settlement_on "
                        + "FROM financial_account a ORDER BY a.id"); ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                String id = rows.getString("id");
                LocalDate openingOn = strictBusinessDate(rows.getString("opening_on"));
                accounts.put(id, new SetupAccount(id, rows.getString("name"), openingOn,
                        rows.getLong("opening_balance_minor"), rows.getInt("is_system") == 1,
                        rows.getString("archived_at"), rows.getLong("revision"),
                        rows.getString("earliest_settlement_on")));
            }
        }
        SetupAccount defaultAccount = accounts.get(LedgerInitializationMigration.DEFAULT_ACCOUNT_ID);
        if (defaultAccount == null) throw new PersistenceException("default account is missing");

        LocalDate earliestObserved = null;
        boolean referencedSeed = false;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT occurred_on,settlement_on,account_id,settlement_mode FROM finance_record ORDER BY id");
                ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                earliestObserved = minDate(earliestObserved, strictBusinessDate(rows.getString("occurred_on")));
                String settlement = rows.getString("settlement_on");
                if (settlement != null) earliestObserved = minDate(earliestObserved, strictBusinessDate(settlement));
                if (LedgerInitializationMigration.DEFAULT_ACCOUNT_ID.equals(rows.getString("account_id"))
                        && "PAID_FROM_ACCOUNT".equals(rows.getString("settlement_mode"))) referencedSeed = true;
            }
        }
        boolean untouchedSeed = isOriginalDefaultAccount(defaultAccount) && !referencedSeed;
        for (SetupAccount account : accounts.values()) {
            if (!untouchedSeed || !LedgerInitializationMigration.DEFAULT_ACCOUNT_ID.equals(account.id)) {
                earliestObserved = minDate(earliestObserved, account.openingOn);
            }
        }
        return new SetupFacts(state, startOn, accounts, defaultAccount, earliestObserved);
    }

    private static String readSetupState(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT setup_state FROM ledger_setting WHERE id=1");
                ResultSet row = query.executeQuery()) {
            return row.next() ? row.getString(1) : null;
        }
    }

    private static boolean isOriginalDefaultAccount(SetupAccount account) {
        return "现金储备".equals(account.name) && account.openingBalanceMinor == 0L && account.system
                && account.archivedAt == null && account.revision == 0L;
    }

    private void validateSetupDraft(Connection connection, LedgerInitializationDraft draft, String state,
            SetupFacts facts) throws SQLException, PersistenceException, LedgerInitializationException {
        if (draft.getLedgerStartOn() == null) throw initializationValidation("ledgerStartOn", "请选择账本起始日。");
        if (draft.getDefaultAccountOpeningOn() == null) {
            throw initializationValidation("defaultAccountOpeningOn", "请选择默认账户开户日。");
        }
        if (draft.getLedgerStartOn().isAfter(businessToday())) {
            throw initializationValidation("ledgerStartOn", "账本起始日不能晚于今天。");
        }
        if (draft.getDefaultAccountOpeningOn().isBefore(draft.getLedgerStartOn())) {
            throw initializationValidation("defaultAccountOpeningOn", "默认账户开户日不能早于账本起始日。");
        }
        if (draft.getAccountName() != null
                && (draft.getAccountName().trim().isEmpty()
                    || draft.getAccountName().trim().codePointCount(0, draft.getAccountName().trim().length()) > 100)) {
            throw initializationValidation("accountName", "账户名称长度必须为 1 到 100 个字符。");
        }
        parseOpeningBalance(draft.getOpeningBalance());
        if ("PENDING".equals(state)) {
            if (draft.isConfirmExistingData() || !draft.getAccountOpeningDates().isEmpty()) {
                throw initializationValidation("confirmExistingData", "新账本不能提交历史数据审查确认。");
            }
            if (!draft.getDefaultAccountOpeningOn().equals(draft.getLedgerStartOn())) {
                throw initializationValidation("defaultAccountOpeningOn", "新账本的默认账户开户日必须与起始日一致。");
            }
        } else {
            if (!draft.isConfirmExistingData()) {
                throw new LedgerInitializationException(409, "REVIEW_CONFIRMATION_REQUIRED", "请先确认现有账本资料。");
            }
            List<String> expected = new ArrayList<>();
            for (SetupAccount account : facts.accounts.values()) {
                if (!LedgerInitializationMigration.DEFAULT_ACCOUNT_ID.equals(account.id)
                        && account.earliestSettlementOn != null
                        && account.openingOn.toString().compareTo(account.earliestSettlementOn) > 0) {
                    expected.add(account.id);
                }
            }
            List<String> submitted = new ArrayList<>(draft.getAccountOpeningDates().keySet());
            Collections.sort(expected);
            if (!expected.equals(submitted)) {
                throw new LedgerInitializationException(409, "REVIEW_CONFIRMATION_REQUIRED",
                        "需修正的账户列表已变化，请刷新审查内容后重试。");
            }
        }
        LocalDate earliestHistory = earliestHistoricalDate(connection);
        if (earliestHistory != null && draft.getLedgerStartOn().isAfter(earliestHistory)) {
            throw initializationValidation("ledgerStartOn", "账本起始日不能晚于已有记录日期 " + earliestHistory + "。");
        }
        for (SetupAccount account : facts.accounts.values()) {
            LocalDate openingOn = LedgerInitializationMigration.DEFAULT_ACCOUNT_ID.equals(account.id)
                    ? draft.getDefaultAccountOpeningOn()
                    : draft.getAccountOpeningDates().getOrDefault(account.id, account.openingOn);
            if (openingOn.isBefore(draft.getLedgerStartOn())) {
                throw initializationValidation(LedgerInitializationMigration.DEFAULT_ACCOUNT_ID.equals(account.id)
                        ? "defaultAccountOpeningOn" : "accountOpeningDates." + account.id,
                        "账户开户日不能早于账本起始日。");
            }
            if (account.earliestSettlementOn != null
                    && openingOn.toString().compareTo(account.earliestSettlementOn) > 0) {
                throw initializationValidation(LedgerInitializationMigration.DEFAULT_ACCOUNT_ID.equals(account.id)
                        ? "defaultAccountOpeningOn" : "accountOpeningDates." + account.id,
                        "账户开户日不能晚于已有结算日 " + account.earliestSettlementOn + "。");
            }
        }
    }

    private LocalDate earliestHistoricalDate(Connection connection) throws SQLException, PersistenceException {
        LocalDate earliest = null;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT occurred_on,settlement_on FROM finance_record ORDER BY id"); ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                earliest = minDate(earliest, strictBusinessDate(rows.getString("occurred_on")));
                String settlementOn = rows.getString("settlement_on");
                if (settlementOn != null) earliest = minDate(earliest, strictBusinessDate(settlementOn));
            }
        }
        return earliest;
    }

    private void updateDefaultAccount(Connection connection, SetupAccount account, LedgerInitializationDraft draft,
            String timestamp) throws SQLException, LedgerInitializationException {
        String name = draft.getAccountName() == null ? account.name : draft.getAccountName().trim();
        long balanceMinor = parseOpeningBalance(draft.getOpeningBalance());
        boolean changed = !name.equals(account.name) || !draft.getDefaultAccountOpeningOn().equals(account.openingOn)
                || balanceMinor != account.openingBalanceMinor;
        if (!changed) return;
        try (PreparedStatement duplicate = connection.prepareStatement(
                "SELECT 1 FROM financial_account WHERE id<>? AND archived_at IS NULL AND name=? COLLATE NOCASE")) {
            duplicate.setString(1, account.id);
            duplicate.setString(2, name);
            try (ResultSet rows = duplicate.executeQuery()) {
                if (rows.next()) throw new LedgerInitializationException(409, "REFERENCE_CONFLICT",
                        "已有账户使用了这个名称。", Collections.emptyMap(),
                        Collections.singletonMap("reason", "ACCOUNT_NAME_CONFLICT"));
            }
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE financial_account SET name=?,opening_on=?,opening_balance_minor=?,updated_at=?,revision=revision+1 WHERE id=?")) {
            update.setString(1, name);
            update.setString(2, draft.getDefaultAccountOpeningOn().toString());
            update.setLong(3, balanceMinor);
            update.setString(4, timestamp);
            update.setString(5, account.id);
            if (update.executeUpdate() != 1) throw new SQLException("default account is missing");
        }
    }

    private void updateReviewedAccountDates(Connection connection, LedgerInitializationDraft draft,
            SetupFacts facts, String timestamp) throws SQLException {
        for (Map.Entry<String, LocalDate> entry : draft.getAccountOpeningDates().entrySet()) {
            SetupAccount account = facts.accounts.get(entry.getKey());
            if (account != null && !account.openingOn.equals(entry.getValue())) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE financial_account SET opening_on=?,updated_at=?,revision=revision+1 WHERE id=?")) {
                    update.setString(1, entry.getValue().toString());
                    update.setString(2, timestamp);
                    update.setString(3, account.id);
                    update.executeUpdate();
                }
            }
        }
    }

    private Map<String, Object> defaultAccountSetupMap(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id,name,opening_on,opening_balance_minor,revision FROM financial_account WHERE id=?")) {
            query.setString(1, LedgerInitializationMigration.DEFAULT_ACCOUNT_ID);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) throw new SQLException("default account is missing");
                Map<String, Object> account = new LinkedHashMap<>();
                account.put("id", row.getString("id"));
                account.put("name", row.getString("name"));
                account.put("openingOn", row.getString("opening_on"));
                account.put("openingBalance", formatMoney(row.getLong("opening_balance_minor")));
                account.put("revision", row.getLong("revision"));
                return account;
            }
        }
    }

    private LedgerInitializationApiResult initializationResult(int status, Map<String, Object> data, long dataRevision)
            throws LedgerInitializationException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("dataRevision", dataRevision);
        body.put("meta", meta);
        try {
            return new LedgerInitializationApiResult(status, objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException ex) {
            throw initializationUnavailable();
        }
    }

    private void validateInitializationRequest(LedgerInitializationDraft draft,
            LedgerInitializationMutation mutation) throws LedgerInitializationException {
        if (draft == null || mutation == null || !isLowercaseUuid(mutation.getIdempotencyKey())
                || mutation.getRequestHash() == null || !mutation.getRequestHash().matches("[0-9a-f]{64}")) {
            throw initializationValidation("request", "初始化请求或幂等信息无效。");
        }
        String previous = null;
        for (String id : draft.getAccountOpeningDates().keySet()) {
            if (!isLowercaseUuid(id) || (previous != null && previous.compareTo(id) >= 0)
                    || draft.getAccountOpeningDates().get(id) == null) {
                throw initializationValidation("accountOpeningDates", "账户开户日必须按账户 ID 升序且不可重复。");
            }
            previous = id;
        }
    }

    private static long parseOpeningBalance(String value) throws LedgerInitializationException {
        try {
            if (value == null || !value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]{1,2})?")) throw new IllegalArgumentException();
            return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        } catch (RuntimeException ex) {
            throw initializationValidation("openingBalance", "期初余额必须是最多两位小数的金额。");
        }
    }

    private static LocalDate strictBusinessDate(String value) throws PersistenceException {
        try {
            LocalDate date = LocalDate.parse(value);
            if (!date.toString().equals(value)) throw new IllegalArgumentException();
            return date;
        } catch (RuntimeException ex) {
            throw new PersistenceException("ledger date is invalid", ex);
        }
    }

    private static LocalDate minDate(LocalDate current, LocalDate candidate) {
        return current == null || candidate.isBefore(current) ? candidate : current;
    }

    private LocalDate businessToday() { return LocalDate.now(clock.withZone(BUSINESS_ZONE)); }

    private static LedgerInitializationException initializationValidation(String field, String message) {
        return new LedgerInitializationException(400, "VALIDATION_FAILED", "请求参数无效。",
                Collections.singletonMap(field, message), Collections.emptyMap());
    }

    private static LedgerInitializationException initializationUnavailable() {
        return new LedgerInitializationException(503, "SERVICE_UNAVAILABLE", "本地数据暂时不可用。");
    }

    @Override
    public ProfileApiResult list(boolean includeArchived, int limit, String cursor) throws ProfileException {
        if (limit < 1 || limit > 200) {
            throw validation("limit", "必须在 1 到 200 之间。");
        }
        gate.readLock().lock();
        try (Connection connection = catalog.openConnection()) {
            CatalogState state = catalog.readState(connection);
            if (activeContext != null) {
                requireActiveContext(state);
            }
            Cursor parsedCursor = cursor == null ? null : Cursor.parse(cursor, includeArchived, state.getRevision());
            List<ProfileView> views = profileViews(state, includeArchived);
            int start = startAfterCursor(views, parsedCursor);
            int end = Math.min(views.size(), start + limit);
            List<Map<String, Object>> items = new ArrayList<>();
            for (int index = start; index < end; index++) {
                items.add(views.get(index).toMap());
            }
            boolean hasMore = end < views.size();
            String nextCursor = hasMore ? Cursor.from(views.get(end - 1), includeArchived, state.getRevision()).encode() : null;
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("nextCursor", nextCursor);
            page.put("hasMore", hasMore);
            page.put("limit", limit);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", items);
            data.put("activeProfileId", state.getActiveProfileId());
            data.put("page", page);
            Long dataRevision = activeContext == null ? null : activeContext.snapshot.getDataRevision();
            return success(200, data, meta(dataRevision, state.getRevision()), null, null);
        } catch (PersistenceException | SQLException ex) {
            throw unavailable(ex);
        } finally {
            gate.readLock().unlock();
        }
    }

    @Override
    public ProfileApiResult create(String id, String name, ProfileMutation mutation) throws ProfileException {
        requireMutableContext();
        validateProfileId(id);
        String normalizedName = validateName(name);
        validateMutation(mutation, "POST", "/api/v1/profiles");
        gate.writeLock().lock();
        try (Connection connection = catalog.openConnection()) {
            MutationCommit commit = transactions.write(connection, tx -> {
                Instant now = Instant.now(clock);
                catalog.removeExpiredOperations(tx, now);
                ProfileApiResult replay = replayOrConflict(tx, mutation, now);
                if (replay != null) {
                    return new MutationCommit(replay, null);
                }
                CatalogState state = catalog.readState(tx);
                requireActiveContext(state);
                if (state.findProfile(id) != null) {
                    throw referenceConflict("PROFILE_ID_EXISTS");
                }
                String relativeDirectory = "Profiles/" + id;
                Path directory = profileBootstrap.resolveProfileDirectory(id, relativeDirectory);
                if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                    throw referenceConflict("PROFILE_DIRECTORY_CONFLICT");
                }
                BootstrapSnapshot targetSnapshot;
                try {
                    Files.createDirectory(directory);
                } catch (FileAlreadyExistsException ex) {
                    throw new ProfileException(409, "REFERENCE_CONFLICT", "新用户空间无法创建。",
                            Collections.emptyMap(), Collections.singletonMap("reason", "PROFILE_DIRECTORY_CONFLICT"));
                } catch (IOException ex) {
                    throw new ProfileException(503, "SERVICE_UNAVAILABLE", "新用户空间暂时无法创建。");
                }
                try {
                    targetSnapshot = ledgerBootstrap.open(directory.resolve("ledger.db"), id);
                } catch (PersistenceException ex) {
                    throw new ProfileException(503, "SERVICE_UNAVAILABLE", "新用户空间暂时无法创建。");
                }
                String timestamp = now.toString();
                CatalogProfile created = new CatalogProfile(id, normalizedName, relativeDirectory, timestamp, timestamp, null, 0L);
                catalog.insertProfile(tx, created);
                catalog.incrementProfileRevision(tx, state.getActiveProfileId());
                catalog.activateProfile(tx, id, state.getRevision(), timestamp);
                long catalogRevision = state.getRevision() + 1;
                ProfileApiResult result = success(201,
                        createOrActivateData(new ProfileView(created, "ACTIVE"), state.getActiveProfileId()),
                        meta(targetSnapshot.getDataRevision(), catalogRevision), "\"0\"", "/api/v1/profiles/" + id);
                catalog.insertCatalogOperation(tx, operationFrom(mutation, result, now));
                return new MutationCommit(result, new ActiveProfileContext(targetSnapshot,
                        directory.resolve("ledger.db").toAbsolutePath().normalize()));
            });
            if (commit.context != null) {
                activeContext = commit.context;
            }
            return commit.result;
        } catch (ProfileException ex) {
            throw ex;
        } catch (PersistenceException | SQLException ex) {
            throw unavailable(ex);
        } catch (Exception ex) {
            throw internal(ex);
        } finally {
            gate.writeLock().unlock();
        }
    }

    @Override
    public ProfileApiResult activate(String id, long expectedRevision, ProfileMutation mutation) throws ProfileException {
        requireMutableContext();
        validateProfileId(id);
        validateMutation(mutation, "POST", "/api/v1/profiles/" + id + "/activate");
        gate.writeLock().lock();
        try (Connection connection = catalog.openConnection()) {
            MutationCommit commit = transactions.write(connection, tx -> {
                Instant now = Instant.now(clock);
                catalog.removeExpiredOperations(tx, now);
                ProfileApiResult replay = replayOrConflict(tx, mutation, now);
                if (replay != null) {
                    return new MutationCommit(replay, null);
                }
                CatalogState state = catalog.readState(tx);
                requireActiveContext(state);
                CatalogProfile target = requireAvailableProfile(state, id);
                requireRevision(target, expectedRevision);
                if (id.equals(state.getActiveProfileId())) {
                    ProfileApiResult result = success(200,
                            createOrActivateData(new ProfileView(target, "ACTIVE"), state.getActiveProfileId()),
                            meta(activeContext.snapshot.getDataRevision(), state.getRevision()), etag(target), null);
                    catalog.insertCatalogOperation(tx, operationFrom(mutation, result, now));
                    return new MutationCommit(result, null);
                }
                BootstrapSnapshot targetSnapshot = validateTargetLedger(target);
                String timestamp = now.toString();
                catalog.incrementProfileRevision(tx, state.getActiveProfileId());
                catalog.touchAndIncrementProfile(tx, id, timestamp);
                catalog.activateProfile(tx, id, state.getRevision(), timestamp);
                CatalogProfile activated = new CatalogProfile(target.getId(), target.getName(), target.getRelativeDirectory(),
                        target.getCreatedAt(), timestamp, null, target.getRevision() + 1);
                long catalogRevision = state.getRevision() + 1;
                ProfileApiResult result = success(200,
                        createOrActivateData(new ProfileView(activated, "ACTIVE"), state.getActiveProfileId()),
                        meta(targetSnapshot.getDataRevision(), catalogRevision), etag(activated), null);
                catalog.insertCatalogOperation(tx, operationFrom(mutation, result, now));
                Path ledgerFile = profileBootstrap.resolveProfileDirectory(id, target.getRelativeDirectory())
                        .resolve("ledger.db").toAbsolutePath().normalize();
                return new MutationCommit(result, new ActiveProfileContext(targetSnapshot, ledgerFile));
            });
            if (commit.context != null) {
                activeContext = commit.context;
            }
            return commit.result;
        } catch (ProfileException ex) {
            throw ex;
        } catch (PersistenceException ex) {
            throw unavailable(ex);
        } catch (Exception ex) {
            throw internal(ex);
        } finally {
            gate.writeLock().unlock();
        }
    }

    @Override
    public ProfileApiResult archive(String id, long expectedRevision, ProfileMutation mutation) throws ProfileException {
        requireMutableContext();
        validateProfileId(id);
        validateMutation(mutation, "DELETE", "/api/v1/profiles/" + id);
        gate.writeLock().lock();
        try (Connection connection = catalog.openConnection()) {
            MutationCommit commit = transactions.write(connection, tx -> {
                Instant now = Instant.now(clock);
                catalog.removeExpiredOperations(tx, now);
                ProfileApiResult replay = replayOrConflict(tx, mutation, now);
                if (replay != null) {
                    return new MutationCommit(replay, null);
                }
                CatalogState state = catalog.readState(tx);
                requireActiveContext(state);
                CatalogProfile target = state.findProfile(id);
                if (target == null) {
                    throw new ProfileException(404, "NOT_FOUND", "用户空间不存在。");
                }
                if (target.getArchivedAt() != null) {
                    throw referenceConflict("PROFILE_ALREADY_ARCHIVED");
                }
                requireRevision(target, expectedRevision);
                if (id.equals(state.getActiveProfileId())) {
                    throw new ProfileException(400, "VALIDATION_FAILED", "不能归档当前用户空间。",
                            Collections.singletonMap("id", "不能归档当前用户空间。"), Collections.emptyMap());
                }
                String timestamp = now.toString();
                catalog.archiveAndIncrementProfile(tx, id, timestamp);
                catalog.activateProfile(tx, state.getActiveProfileId(), state.getRevision(), timestamp);
                CatalogProfile archived = new CatalogProfile(target.getId(), target.getName(), target.getRelativeDirectory(),
                        target.getCreatedAt(), target.getLastOpenedAt(), timestamp, target.getRevision() + 1);
                long catalogRevision = state.getRevision() + 1;
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("profile", new ProfileView(archived, "ARCHIVED").toMap());
                ProfileApiResult result = success(200, data,
                        meta(activeContext.snapshot.getDataRevision(), catalogRevision), etag(archived), null);
                catalog.insertCatalogOperation(tx, operationFrom(mutation, result, now));
                return new MutationCommit(result, null);
            });
            return commit.result;
        } catch (ProfileException ex) {
            throw ex;
        } catch (PersistenceException ex) {
            throw unavailable(ex);
        } catch (Exception ex) {
            throw internal(ex);
        } finally {
            gate.writeLock().unlock();
        }
    }

    @Override
    public SettingsApiResult read() throws SettingsException {
        gate.readLock().lock();
        try {
            requireSettingsContext();
            try (Connection connection = new SqliteDatabase(activeContext.ledgerFile).open()) {
                return settingsSuccess(settings.readState(connection));
            }
        } catch (SettingsException ex) {
            throw ex;
        } catch (PersistenceException | IOException | SQLException ex) {
            throw settingsUnavailable();
        } finally {
            gate.readLock().unlock();
        }
    }

    @Override
    public SettingsApiResult update(long expectedRevision, SettingsPatch patch, SettingsMutation mutation)
            throws SettingsException {
        validateSettingsRevision(expectedRevision);
        validateSettingsPatch(patch);
        validateSettingsMutation(mutation);
        gate.writeLock().lock();
        try {
            requireSettingsContext();
            try (Connection catalogConnection = catalog.openConnection();
                    Connection connection = new SqliteDatabase(activeContext.ledgerFile).open()) {
                Instant now = Instant.now(clock);
                if (catalog.findCatalogOperation(catalogConnection, mutation.getIdempotencyKey(), now) != null) {
                    throw settingsIdempotencyConflict();
                }
                SettingsCommit commit = transactions.write(connection, tx -> {
                    settings.removeExpiredOperations(tx, now);
                    LedgerOperation existing = settings.findOperation(tx, mutation.getIdempotencyKey(), now);
                    if (existing != null) {
                        if (sameSettingsRequest(existing, mutation)) {
                            return new SettingsCommit(replaySettings(existing), null);
                        }
                        throw settingsIdempotencyConflict();
                    }
                    SettingsState state = settings.readState(tx);
                    SettingsRecord current = state.getSettings();
                    if (current.getRevision() != expectedRevision) {
                        throw settingsRevisionConflict(current.getRevision());
                    }
                    SettingsRecord next = applyPatch(current, patch, now.toString());
                    boolean changed = !sameSettings(current, next);
                    SettingsApiResult result;
                    ActiveProfileContext nextContext = null;
                    if (changed) {
                        settings.updateSettings(tx, next, current.getRevision());
                        settings.advanceLedgerMeta(tx, state.getDataRevision(), now.toString());
                        long nextDataRevision = state.getDataRevision() + 1;
                        result = settingsSuccess(new SettingsState(next, nextDataRevision));
                        nextContext = new ActiveProfileContext(new BootstrapSnapshot(
                                activeContext.snapshot.getProfileId(), activeContext.snapshot.getSchemaVersion(),
                                nextDataRevision, activeContext.snapshot.getSetupState(),
                                activeContext.snapshot.getLedgerStartOn()), activeContext.ledgerFile);
                    } else {
                        result = settingsSuccess(state);
                    }
                    settings.insertOperation(tx, settingsOperation(mutation, result,
                            activeContext.snapshot.getProfileId(), now));
                    return new SettingsCommit(result, nextContext);
                });
                if (commit.context != null) {
                    activeContext = commit.context;
                }
                return commit.result;
            }
        } catch (SettingsException ex) {
            throw ex;
        } catch (PersistenceException | IOException | SQLException ex) {
            throw settingsUnavailable();
        } catch (Exception ex) {
            throw settingsInternal();
        } finally {
            gate.writeLock().unlock();
        }
    }

    @Override
    public MetricApiResult listMetrics(MetricListQuery query) throws MetricException {
        if (query == null || !("ACTIVE".equals(query.getStatus()) || "ARCHIVED".equals(query.getStatus()))) {
            throw metricValidation("status", "只能是 ACTIVE 或 ARCHIVED。");
        }
        if (query.getLimit() < 1 || query.getLimit() > 200) {
            throw metricValidation("limit", "必须在 1 到 200 之间。");
        }
        gate.readLock().lock();
        try {
            requireMetricContext();
            LedgerMetricsRepository repository = new LedgerMetricsRepository(activeContext.ledgerFile);
            try (Connection connection = repository.openConnection()) {
                Map<String, VisibilityRecord> visibility = visibilityById(repository.listVisibility(connection));
                long dataRevision = repositoryDataRevision(connection);
                List<MetricDefinitionRecord> filtered = new ArrayList<>();
                for (MetricDefinitionRecord metric : repository.listMetricDefinitions(connection, true)) {
                    boolean archived = metric.archivedAt != null;
                    if (("ARCHIVED".equals(query.getStatus()) && !archived)
                            || ("ACTIVE".equals(query.getStatus()) && archived)) continue;
                    VisibilityRecord visible = visibility.get(metric.id);
                    if (query.getDashboardEnabled() != null && (visible == null
                            || visible.dashboardEnabled != query.getDashboardEnabled().booleanValue())) continue;
                    filtered.add(metric);
                }
                int start = metricCursorStart(filtered, query, dataRevision);
                int end = Math.min(filtered.size(), start + query.getLimit());
                List<Map<String, Object>> items = new ArrayList<>();
                for (int index = start; index < end; index++) {
                    MetricDefinitionRecord metric = filtered.get(index);
                    items.add(metricSummaryView(metric, visibility.get(metric.id), repository, connection));
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("items", items);
                Map<String, Object> page = new LinkedHashMap<>();
                boolean hasMore = end < filtered.size();
                page.put("nextCursor", hasMore ? metricCursor(filtered.get(end - 1), query, dataRevision) : null);
                page.put("hasMore", hasMore); page.put("limit", query.getLimit());
                data.put("page", page);
                return metricSuccess(200, data, dataRevision, null, null);
            }
        } catch (MetricException ex) {
            throw ex;
        } catch (PersistenceException | SQLException ex) {
            throw metricUnavailable();
        } finally {
            gate.readLock().unlock();
        }
    }

    @Override
    public MetricApiResult getMetric(String id, boolean includeArchived) throws MetricException {
        if (id == null || id.trim().isEmpty()) throw metricValidation("id", "指标 ID 不能为空。");
        gate.readLock().lock();
        try {
            requireMetricContext();
            LedgerMetricsRepository repository = new LedgerMetricsRepository(activeContext.ledgerFile);
            try (Connection connection = repository.openConnection()) {
                MetricDefinitionRecord metric = repository.findMetric(connection, id);
                if (metric == null || (metric.archivedAt != null && !includeArchived)) {
                    throw new MetricException(404, "NOT_FOUND", "指标不存在。");
                }
                VisibilityRecord visibility = repository.findVisibility(connection, id);
                return metricSuccess(200, Collections.singletonMap("metric", metricDetailView(metric, visibility, repository, connection)),
                        repositoryDataRevision(connection), quoteEtag(metric.revision), null);
            }
        } catch (MetricException ex) {
            throw ex;
        } catch (PersistenceException | SQLException ex) {
            throw metricUnavailable();
        } finally {
            gate.readLock().unlock();
        }
    }

    @Override
    public MetricApiResult validateFormula(FormulaDraftRequest draft) throws MetricException {
        if (draft == null) throw metricValidation("body", "公式草稿不能为空。");
        validateFormulaDraftShape(draft);
        gate.readLock().lock();
        try {
            requireMetricContext();
            LedgerMetricsRepository repository = new LedgerMetricsRepository(activeContext.ledgerFile);
            try (Connection connection = repository.openConnection()) {
                FormulaValidationResult validation = validateFormula(connection, draft.getCandidateMetricId(), draft.getFormula());
                requireFormulaValid(validation);
                ResolvedPeriod period = new PeriodResolver(clock).resolve(
                        new DashboardPeriod(draft.getGranularity(), draft.getAnchor()));
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("valid", true);
                    data.put("formula", formulaDraftView(draft.getFormula()));
                data.put("dependencies", dependencyViews(validation.getDependencies(), repository, connection));
                data.put("periodBehavior", "PERIOD");
                data.put("period", periodView(period));
                return metricSuccess(200, data, repositoryDataRevision(connection), null, null);
            }
        } catch (MetricException ex) { throw ex;
        } catch (PersistenceException | SQLException ex) { throw metricUnavailable();
        } finally { gate.readLock().unlock(); }
    }

    @Override
    public MetricApiResult previewFormula(FormulaDraftRequest draft) throws MetricException {
        if (draft == null) throw metricValidation("body", "公式草稿不能为空。");
        validateFormulaDraftShape(draft);
        gate.readLock().lock();
        try {
            requireMetricContext();
            LedgerMetricsRepository repository = new LedgerMetricsRepository(activeContext.ledgerFile);
            try (Connection connection = repository.openConnection()) {
                FormulaValidationResult validation = validateFormula(connection, draft.getCandidateMetricId(), draft.getFormula());
                requireFormulaValid(validation);
                ResolvedPeriod period = new PeriodResolver(clock).resolve(
                        new DashboardPeriod(draft.getGranularity(), draft.getAnchor()));
                MetricFactsRecord facts = repository.aggregateFacts(connection, period);
                FormulaEvaluator evaluator = new FormulaEvaluator();
                Map<String, FormulaEvaluationResult> memo = new HashMap<>();
                Map<String, BigDecimal> metricValues = new HashMap<>();
                for (FormulaReference reference : validation.getDependencies()) {
                    if (reference.getKind() != FormulaReference.Kind.METRIC) continue;
                    FormulaEvaluationResult value = calculateMetricValue(reference.getKey(), repository, connection,
                            facts, new SystemMetricCalculator(), evaluator, memo, new HashSet<>());
                    if (value.isReady()) metricValues.put(reference.getKey(), value.getValue());
                }
                FormulaEvaluationResult result = evaluator.evaluate(draft.getFormula(), formulaContext(metricValues, facts));
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("value", formulaValueView(result, draft.getDisplayFormat(), precisionFor(draft.getDisplayFormat())));
                data.put("components", previewComponents(validation.getDependencies(), metricValues, facts,
                        repository, connection));
                return metricSuccess(200, data, facts.dataRevision, null, null);
            }
        } catch (MetricException ex) { throw ex;
        } catch (PersistenceException | SQLException ex) { throw metricUnavailable();
        } finally { gate.readLock().unlock(); }
    }

    @Override
    public MetricApiResult listFormulaVersions(String formulaId, int limit, String cursor) throws MetricException {
        if (formulaId == null || formulaId.trim().isEmpty()) throw metricValidation("formulaId", "公式 ID 不能为空。");
        if (limit < 1 || limit > 100) throw metricValidation("limit", "必须在 1 到 100 之间。");
        if (cursor != null && !cursor.trim().isEmpty()) throw metricValidation("cursor", "暂不支持无效游标。");
        gate.readLock().lock();
        try {
            requireMetricContext();
            LedgerMetricsRepository repository = new LedgerMetricsRepository(activeContext.ledgerFile);
            try (Connection connection = repository.openConnection()) {
                MetricDefinitionRecord metric = repository.findMetric(connection, formulaId);
                if (metric == null || metric.currentFormulaVersionId == null
                        || (metric.archivedAt != null)) throw new MetricException(404, "NOT_FOUND", "公式不存在。");
                List<Map<String, Object>> items = new ArrayList<>();
                for (FormulaVersionRecord version : repository.listFormulaVersions(connection, formulaId, limit)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", version.id); item.put("version", version.version);
                    try {
                        FormulaNode root = FormulaStorageMapper.fromAst(objectMapper.readTree(version.astJson));
                        item.put("ast", FormulaHttpMapper.toAst(root));
                        item.put("tokens", CanonicalFormulaTokens.from(root));
                    } catch (IOException | RuntimeException ex) { throw metricInternal(); }
                    item.put("dependencies", dependencyViews(dependencyReferences(
                            repository.listDependencies(connection, version.id)), repository, connection));
                    item.put("createdAt", version.createdAt);
                    items.add(item);
                }
                Map<String, Object> page = new LinkedHashMap<>();
                page.put("nextCursor", null); page.put("hasMore", false); page.put("limit", limit);
                Map<String, Object> data = new LinkedHashMap<>(); data.put("items", items); data.put("page", page);
                return metricSuccess(200, data, repositoryDataRevision(connection), null, null);
            }
        } catch (MetricException ex) { throw ex;
        } catch (PersistenceException | SQLException ex) { throw metricUnavailable();
        } finally { gate.readLock().unlock(); }
    }

    private void validateFormulaDraftShape(FormulaDraftRequest draft) throws MetricException {
        if (draft.getCandidateMetricId() == null || draft.getCandidateMetricId().trim().isEmpty()) {
            throw metricValidation("candidateMetricId", "必须提供候选指标 ID。");
        }
        if (draft.getDisplayFormat() == null || !("CURRENCY".equals(draft.getDisplayFormat())
                || "PERCENT".equals(draft.getDisplayFormat()) || "NUMBER".equals(draft.getDisplayFormat())
                || "INTEGER".equals(draft.getDisplayFormat()))) {
            throw metricValidation("displayFormat", "显示格式无效。");
        }
        if (draft.getFormula() == null) throw metricValidation("formula.ast", "公式不能为空。");
        if (draft.getGranularity() == null) throw metricValidation("granularity", "必须提供粒度。");
        if (draft.getAnchor() == null) throw metricValidation("anchor", "必须提供期间锚点。");
    }

    private static int precisionFor(String format) {
        if ("INTEGER".equals(format)) return 0;
        if ("CURRENCY".equals(format)) return 2;
        return 2;
    }

    private static Map<String, Object> formulaValueView(FormulaEvaluationResult result, String format, int precision) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("value", result.getValue() == null ? null : result.getValue().toPlainString());
        value.put("displayFormat", format); value.put("precision", precision);
        value.put("dataStatus", result.getStatus().name()); return value;
    }

    private static Map<String, Object> formulaDraftView(FormulaNode node) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("ast", FormulaHttpMapper.toAst(node));
        value.put("tokens", CanonicalFormulaTokens.from(node));
        return value;
    }

    private List<Map<String, Object>> dependencyViews(Set<FormulaReference> references,
            LedgerMetricsRepository repository, Connection connection) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (FormulaReference reference : references) {
            Map<String, Object> value = new LinkedHashMap<>(); value.put("kind", reference.getKind().name());
            value.put("key", reference.getKey());
            value.put("label", dependencyLabel(reference, repository, connection)); result.add(value);
        }
        return result;
    }

    @Override
    public DashboardApiResult readLayout() throws DashboardException {
        gate.readLock().lock();
        try {
            requireDashboardContext();
            LedgerMetricsRepository repository = new LedgerMetricsRepository(activeContext.ledgerFile);
            try (Connection connection = repository.openConnection()) {
                LayoutRecord layout = repository.readLayout(connection);
                if (layout == null) throw new DashboardException(503, "SERVICE_UNAVAILABLE", "总览布局不可用。");
                List<MetricDefinitionRecord> activeMetrics = repository.listMetricDefinitions(connection, false);
                Map<String, VisibilityRecord> visibility = visibilityById(repository.listVisibility(connection));
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("layout", dashboardLayoutView(layout, activeMetrics, visibility));
                return dashboardSuccess(200, data, repositoryDataRevision(connection), quoteEtag(layout.revision), null);
            }
        } catch (DashboardException ex) {
            throw ex;
        } catch (PersistenceException | SQLException ex) {
            throw dashboardUnavailable();
        } finally {
            gate.readLock().unlock();
        }
    }

    @Override
    public DashboardApiResult readDashboard(MetricGranularity granularity, LocalDate anchor)
            throws DashboardException {
        if (granularity == null) throw new DashboardException(400, "VALIDATION_FAILED", "粒度不能为空。");
        if (anchor == null) throw new DashboardException(400, "VALIDATION_FAILED", "期间锚点不能为空。");
        gate.readLock().lock();
        try {
            requireDashboardContext();
            LedgerMetricsRepository repository = new LedgerMetricsRepository(activeContext.ledgerFile);
            try (Connection connection = repository.openConnection()) {
                ResolvedPeriod period = new PeriodResolver(clock).resolve(new DashboardPeriod(granularity, anchor));
                MetricFactsRecord facts = repository.aggregateFacts(connection, period);
                ResolvedPeriod previous = comparisonPeriod(period);
                MetricFactsRecord previousFacts = repository.aggregateFacts(connection, previous);
                SystemMetricCalculator calculator = new SystemMetricCalculator();
                FormulaEvaluator formulaEvaluator = new FormulaEvaluator();
                Map<String, VisibilityRecord> visibility = visibilityById(repository.listVisibility(connection));
                Map<String, FormulaEvaluationResult> currentMemo = new HashMap<>();
                Map<String, FormulaEvaluationResult> previousMemo = new HashMap<>();
                List<Map<String, Object>> cards = new ArrayList<>();
                LayoutRecord layout = repository.readLayout(connection);
                List<MetricDefinitionRecord> activeMetrics = repository.listMetricDefinitions(connection, false);
                for (MetricDefinitionRecord metric : activeMetrics) {
                    VisibilityRecord visible = visibility.get(metric.id);
                    if (visible == null || !visible.dashboardEnabled) continue;
                    Map<String, Object> card = new LinkedHashMap<>();
                    card.put("widgetId", "metric:" + metric.id);
                    card.put("metric", metricView(metric, visible));
                    boolean hidden = visible.hidden;
                    card.put("presentation", Collections.singletonMap("hidden", hidden));
                    FormulaEvaluationResult value = period.getStatus() == PeriodStatus.FUTURE
                            ? FormulaEvaluationResult.future()
                            : calculateMetricValue(metric.id, repository, connection, facts, calculator,
                                    formulaEvaluator, currentMemo, new HashSet<>());
                    FormulaEvaluationResult previousValue = calculateMetricValue(metric.id, repository, connection,
                            previousFacts, calculator, formulaEvaluator, previousMemo, new HashSet<>());
                    if (!hidden) {
                        card.put("value", metricValue(value, metric));
                        card.put("previousValue", metricValue(previousValue, metric));
                        card.put("change", changeValue(value, previousValue));
                    }
                    card.put("trend", Collections.emptyList());
                    card.put("breakdown", Collections.emptyList());
                    cards.add(card);
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("period", periodView(period));
                data.put("previousPeriod", previousPeriodView(period));
                data.put("comparisonWindow", comparisonWindowView(period));
                data.put("layout", dashboardLayoutView(layout, activeMetrics, visibility));
                data.put("cards", cards);
                return dashboardSuccess(200, data, facts.dataRevision, null, null);
            }
        } catch (DashboardException ex) {
            throw ex;
        } catch (PersistenceException | SQLException ex) {
            throw dashboardUnavailable();
        } finally {
            gate.readLock().unlock();
        }
    }

    @Override
    public DashboardApiResult replaceLayout(long expectedRevision, DashboardLayoutDraft draft,
            DashboardMutation mutation) throws DashboardException {
        requireDashboardContext(); validateDashboardMutation(mutation, "PUT", "/api/v1/dashboard/layout");
        if (draft == null || draft.getItems().isEmpty()) throw dashboardValidationException("items", "布局至少包含一项。");
        validateLayoutItems(draft.getItems());
        gate.writeLock().lock();
        try {
            LedgerMetricsRepository repository = new LedgerMetricsRepository(activeContext.ledgerFile);
            try (Connection connection = repository.openConnection()) {
                validateEnabledLayoutItems(draft.getItems(), repository.listMetricDefinitions(connection, false),
                        visibilityById(repository.listVisibility(connection)));
                DashboardCommit commit = transactions.write(connection, tx -> {
                    Instant now = Instant.now(clock); String timestamp = now.toString();
                    settings.removeExpiredOperations(tx, now);
                    DashboardApiResult replay = replayDashboard(tx, mutation, now);
                    if (replay != null) return new DashboardCommit(replay, -1L);
                    LayoutRecord current = repository.readLayout(tx);
                    if (current == null) throw new DashboardException(503, "SERVICE_UNAVAILABLE", "总览布局不可用。");
                    if (current.revision != expectedRevision) throw dashboardRevision(current.revision);
                    repository.replaceLayout(tx, current.id, expectedRevision, draft.getItems(), timestamp);
                    long before = repositoryDataRevision(tx); settings.advanceLedgerMeta(tx, before, timestamp);
                    LayoutRecord stored = repository.readLayout(tx);
                    DashboardApiResult result = dashboardSuccess(200,
                            Collections.singletonMap("layout", layoutView(stored)), before + 1,
                            quoteEtag(stored.revision), null);
                    settings.insertOperation(tx, dashboardOperation(mutation, result, timestamp, now));
                    return new DashboardCommit(result, before + 1);
                });
                refreshMetricContext(commit.dataRevision); return commit.result;
            }
        } catch (DashboardException ex) { throw ex;
        } catch (PersistenceException | SQLException ex) { throw dashboardUnavailable();
        } catch (Exception ex) { throw dashboardInternal();
        } finally { gate.writeLock().unlock(); }
    }

    @Override
    public DashboardApiResult resetLayout(long expectedRevision, DashboardMutation mutation) throws DashboardException {
        requireDashboardContext(); validateDashboardMutation(mutation, "POST", "/api/v1/dashboard/layout/reset");
        gate.writeLock().lock();
        try {
            LedgerMetricsRepository repository = new LedgerMetricsRepository(activeContext.ledgerFile);
            try (Connection connection = repository.openConnection()) {
                DashboardCommit commit = transactions.write(connection, tx -> {
                    Instant now = Instant.now(clock); String timestamp = now.toString();
                    settings.removeExpiredOperations(tx, now);
                    DashboardApiResult replay = replayDashboard(tx, mutation, now);
                    if (replay != null) return new DashboardCommit(replay, -1L);
                    LayoutRecord current = repository.readLayout(tx);
                    if (current == null) throw new DashboardException(503, "SERVICE_UNAVAILABLE", "总览布局不可用。");
                    if (current.revision != expectedRevision) throw dashboardRevision(current.revision);
                    repository.replaceLayout(tx, current.id, expectedRevision, defaultLayoutItems(repository.listMetricDefinitions(tx, false), repository.listVisibility(tx)), timestamp);
                    long before = repositoryDataRevision(tx); settings.advanceLedgerMeta(tx, before, timestamp);
                    LayoutRecord stored = repository.readLayout(tx);
                    DashboardApiResult result = dashboardSuccess(200, Collections.singletonMap("layout", layoutView(stored)), before + 1,
                            quoteEtag(stored.revision), null);
                    settings.insertOperation(tx, dashboardOperation(mutation, result, timestamp, now));
                    return new DashboardCommit(result, before + 1);
                });
                refreshMetricContext(commit.dataRevision); return commit.result;
            }
        } catch (DashboardException ex) { throw ex;
        } catch (PersistenceException | SQLException ex) { throw dashboardUnavailable();
        } catch (Exception ex) { throw dashboardInternal();
        } finally { gate.writeLock().unlock(); }
    }

    @Override
    public MetricApiResult createMetric(MetricDraft draft, MetricMutation mutation) throws MetricException {
        requireMetricContext();
        validateMetricMutation(mutation, "POST", "/api/v1/metrics");
        validateMetricDraft(draft);
        if (draft == null || draft.getId() == null) throw metricValidation("id", "必须提供 custom-UUID v4 指标 ID。");
        validateMetricId(draft.getId());
        gate.writeLock().lock();
        try {
            LedgerMetricsRepository repository = new LedgerMetricsRepository(activeContext.ledgerFile);
            try (Connection connection = repository.openConnection()) {
                MetricCommit commit = transactions.write(connection, tx -> {
                    Instant now = Instant.now(clock); String timestamp = now.toString();
                    settings.removeExpiredOperations(tx, now);
                    MetricApiResult replay = replayMetric(tx, mutation, now);
                    if (replay != null) return new MetricCommit(replay, -1L);
                    if (repository.findMetric(tx, draft.getId()) != null) throw new MetricException(409, "REFERENCE_CONFLICT", "指标 ID 已存在。");
                    FormulaValidationResult validation = validateFormula(tx, draft.getId(), draft.getFormula());
                    requireFormulaValid(validation);
                    repository.insertFormulaDefinition(tx, draft.getId(), draft.getDisplayFormat(), timestamp);
                    String versionId = UUID.randomUUID().toString();
                    repository.insertFormulaVersion(tx, versionId, draft.getId(), 1,
                            objectMapper.writeValueAsString(FormulaStorageMapper.toAst(draft.getFormula())),
                            objectMapper.writeValueAsString(CanonicalFormulaTokens.from(draft.getFormula())), timestamp);
                    repository.insertDependencies(tx, versionId, dependencyRecords(validation.getDependencies()));
                    repository.insertMetricDefinition(tx, draft.getId(), draft.getName(), draft.getDescription(),
                            draft.getDisplayFormat(), draft.getPrecision(), draft.getPeriodBehavior(), false, timestamp);
                    repository.setCurrentFormulaVersion(tx, draft.getId(), versionId, 0, timestamp);
                    repository.updateVisibility(tx, draft.getId(), draft.isHidden(), draft.isDashboardEnabled(), 1, 0, timestamp);
                    long before = repositoryDataRevision(tx); settings.advanceLedgerMeta(tx, before, timestamp);
                    MetricDefinitionRecord stored = repository.findMetric(tx, draft.getId());
                    MetricApiResult result = metricSuccess(201,
                            Collections.singletonMap("metric", metricDetailView(stored, repository.findVisibility(tx, draft.getId()), repository, tx)), before + 1,
                            quoteEtag(0), "/api/v1/metrics/" + draft.getId());
                    settings.insertOperation(tx, metricOperation(mutation, result, timestamp, now));
                    return new MetricCommit(result, before + 1);
                });
                refreshMetricContext(commit.dataRevision); return commit.result;
            }
        } catch (MetricException ex) { throw ex;
        } catch (PersistenceException | SQLException ex) { throw metricUnavailable();
        } catch (Exception ex) { throw metricInternal();
        } finally { gate.writeLock().unlock(); }
    }

    @Override
    public MetricApiResult updateMetric(String id, long expectedRevision, MetricDraft draft, MetricMutation mutation)
            throws MetricException {
        requireMetricContext();
        validateMetricId(id);
        validateMetricMutation(mutation, "PUT", "/api/v1/metrics/" + id);
        if (draft == null) throw metricValidation("body", "指标替换内容不能为空。");
        validateMetricDraft(draft);
        gate.writeLock().lock();
        try {
            LedgerMetricsRepository repository = new LedgerMetricsRepository(activeContext.ledgerFile);
            try (Connection connection = repository.openConnection()) {
                MetricCommit commit = transactions.write(connection, tx -> {
                    Instant now = Instant.now(clock); String timestamp = now.toString();
                    settings.removeExpiredOperations(tx, now);
                    MetricApiResult replay = replayMetric(tx, mutation, now);
                    if (replay != null) return new MetricCommit(replay, -1L);
                    MetricDefinitionRecord current = repository.findMetric(tx, id);
                    if (current == null || current.archivedAt != null) throw new MetricException(404, "NOT_FOUND", "指标不存在。");
                    if (current.system) throw new MetricException(409, "REFERENCE_CONFLICT", "系统指标定义不可修改。");
                    if (current.revision != expectedRevision) throw metricRevision(current.revision);
                    FormulaValidationResult validation = validateFormula(tx, id, draft.getFormula());
                    requireFormulaValid(validation);
                    String versionId = UUID.randomUUID().toString();
                    int version = repository.nextFormulaVersion(tx, id);
                    String astJson = objectMapper.writeValueAsString(FormulaStorageMapper.toAst(draft.getFormula()));
                    String tokensJson = objectMapper.writeValueAsString(CanonicalFormulaTokens.from(draft.getFormula()));
                    repository.insertFormulaVersion(tx, versionId, id, version, astJson, tokensJson, timestamp);
                    repository.insertDependencies(tx, versionId, dependencyRecords(validation.getDependencies()));
                    long nextRevision = current.revision + 1;
                    repository.updateMetricDefinition(tx, id, draft.getName(), draft.getDescription(), draft.getDisplayFormat(),
                            draft.getPrecision(), draft.getPeriodBehavior(), nextRevision, timestamp);
                    repository.setCurrentFormulaVersion(tx, id, versionId, nextRevision, timestamp);
                    VisibilityRecord visibility = repository.findVisibility(tx, id);
                    repository.updateVisibility(tx, id, draft.isHidden(), draft.isDashboardEnabled(),
                            visibility == null ? 1 : visibility.revision + 1, nextRevision, timestamp);
                    long before = repositoryDataRevision(tx); settings.advanceLedgerMeta(tx, before, timestamp);
                    MetricDefinitionRecord stored = repository.findMetric(tx, id);
                    MetricApiResult result = metricSuccess(200,
                            Collections.singletonMap("metric", metricDetailView(stored, repository.findVisibility(tx, id), repository, tx)), before + 1,
                            quoteEtag(nextRevision), null);
                    settings.insertOperation(tx, metricOperation(mutation, result, timestamp, now));
                    return new MetricCommit(result, before + 1);
                });
                refreshMetricContext(commit.dataRevision);
                return commit.result;
            }
        } catch (MetricException ex) { throw ex;
        } catch (PersistenceException | SQLException ex) { throw metricUnavailable();
        } catch (Exception ex) { throw metricInternal();
        } finally { gate.writeLock().unlock(); }
    }

    @Override
    public MetricApiResult updateSystemVisibility(String id, long expectedRevision, boolean hidden,
            boolean dashboardEnabled, MetricMutation mutation) throws MetricException {
        requireMetricContext();
        if (id == null || id.trim().isEmpty()) throw metricValidation("id", "指标 ID 不能为空。");
        validateMetricMutation(mutation, "PUT", "/api/v1/metrics/" + id);
        gate.writeLock().lock();
        try {
            LedgerMetricsRepository repository = new LedgerMetricsRepository(activeContext.ledgerFile);
            try (Connection connection = repository.openConnection()) {
                MetricCommit commit = transactions.write(connection, tx -> {
                    Instant now = Instant.now(clock); String timestamp = now.toString();
                    settings.removeExpiredOperations(tx, now);
                    MetricApiResult replay = replayMetric(tx, mutation, now);
                    if (replay != null) return new MetricCommit(replay, -1L);
                    MetricDefinitionRecord current = repository.findMetric(tx, id);
                    if (current == null || current.archivedAt != null) throw new MetricException(404, "NOT_FOUND", "指标不存在。");
                    if (!current.system) throw new MetricException(409, "REFERENCE_CONFLICT", "自定义指标必须提交完整公式。");
                    if (current.revision != expectedRevision) throw metricRevision(current.revision);
                    VisibilityRecord previous = repository.findVisibility(tx, id);
                    long nextRevision = current.revision + 1;
                    repository.updateVisibility(tx, id, hidden, dashboardEnabled,
                            previous == null ? 1L : previous.revision + 1L, nextRevision, timestamp);
                    long before = repositoryDataRevision(tx); settings.advanceLedgerMeta(tx, before, timestamp);
                    MetricDefinitionRecord stored = repository.findMetric(tx, id);
                    MetricApiResult result = metricSuccess(200, Collections.singletonMap("metric",
                            metricDetailView(stored, repository.findVisibility(tx, id), repository, tx)), before + 1,
                            quoteEtag(nextRevision), null);
                    settings.insertOperation(tx, metricOperation(mutation, result, timestamp, now));
                    return new MetricCommit(result, before + 1);
                });
                refreshMetricContext(commit.dataRevision); return commit.result;
            }
        } catch (MetricException ex) { throw ex;
        } catch (PersistenceException | SQLException ex) { throw metricUnavailable();
        } catch (Exception ex) { throw metricInternal();
        } finally { gate.writeLock().unlock(); }
    }

    @Override
    public MetricApiResult archiveMetric(String id, long expectedRevision, MetricMutation mutation)
            throws MetricException {
        requireMetricContext(); validateMetricId(id); validateMetricMutation(mutation, "DELETE", "/api/v1/metrics/" + id);
        gate.writeLock().lock();
        try {
            LedgerMetricsRepository repository = new LedgerMetricsRepository(activeContext.ledgerFile);
            try (Connection connection = repository.openConnection()) {
                MetricCommit commit = transactions.write(connection, tx -> {
                    Instant now = Instant.now(clock); String timestamp = now.toString();
                    settings.removeExpiredOperations(tx, now);
                    MetricApiResult replay = replayMetric(tx, mutation, now);
                    if (replay != null) return new MetricCommit(replay, -1L);
                    MetricDefinitionRecord current = repository.findMetric(tx, id);
                    if (current == null || current.archivedAt != null) throw new MetricException(404, "NOT_FOUND", "指标不存在。");
                    if (current.system) throw new MetricException(409, "REFERENCE_CONFLICT", "系统指标不能归档。",
                            Collections.emptyMap(), Collections.singletonMap("reason", "SYSTEM_METRIC"));
                    if (current.revision != expectedRevision) throw metricRevision(current.revision);
                    List<String> references = repository.findMetricReferences(tx, id);
                    if (!references.isEmpty()) throw new MetricException(409, "REFERENCE_CONFLICT", "指标仍被其他指标引用。",
                            Collections.emptyMap(), new LinkedHashMap<String, Object>() {{ put("reason", "METRIC_REFERENCED"); put("metricIds", references); }});
                    repository.archiveMetric(tx, id, current.revision + 1, timestamp);
                    long before = repositoryDataRevision(tx); settings.advanceLedgerMeta(tx, before, timestamp);
                    MetricDefinitionRecord stored = repository.findMetric(tx, id);
                    MetricApiResult result = metricSuccess(200,
                            Collections.singletonMap("metric", metricDetailView(stored, repository.findVisibility(tx, id), repository, tx)), before + 1,
                            quoteEtag(stored.revision), null);
                    settings.insertOperation(tx, metricOperation(mutation, result, timestamp, now));
                    return new MetricCommit(result, before + 1);
                });
                refreshMetricContext(commit.dataRevision); return commit.result;
            }
        } catch (MetricException ex) { throw ex;
        } catch (PersistenceException | SQLException ex) { throw metricUnavailable();
        } catch (Exception ex) { throw metricInternal();
        } finally { gate.writeLock().unlock(); }
    }

    @Override
    public CategoryApiResult listCategories(boolean includeArchived, int limit, String cursor)
            throws CategoryException {
        if (limit < 1 || limit > 200) throw categoryValidation("limit", "必须在 1 到 200 之间。");
        if (cursor != null && cursor.trim().isEmpty()) throw categoryValidation("cursor", "游标无效。");
        gate.readLock().lock();
        try {
            requireCategoryContext();
            LedgerCatalogRepository repo = categoryRepository();
            try (Connection connection = repo.openConnection()) {
                List<CategoryRecord> all = repo.listCategories(connection, includeArchived);
                int start = categoryCursorStart(all, cursor, includeArchived, repo.dataRevision(connection));
                int end = Math.min(all.size(), start + limit);
                List<Map<String, Object>> items = new ArrayList<>();
                for (int i = start; i < end; i++) items.add(categoryMap(all.get(i)));
                Map<String, Object> page = new LinkedHashMap<>();
                page.put("nextCursor", end < all.size()
                        ? categoryCursor(all.get(end - 1), includeArchived, repo.dataRevision(connection)) : null);
                page.put("hasMore", end < all.size());
                page.put("limit", limit);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("items", items); data.put("page", page);
                long revision = repo.dataRevision(connection);
                return categorySuccess(200, data, revision, "\"" + revision + "\"", null);
            }
        } catch (CategoryException ex) {
            throw ex;
        } catch (PersistenceException | SQLException ex) {
            throw categoryUnavailable();
        } finally {
            gate.readLock().unlock();
        }
    }

    @Override
    public CategoryApiResult createCategory(CategoryPatch patch, CategoryMutation mutation)
            throws CategoryException {
        requireCategoryContext();
        validateCategoryMutation(mutation, "POST", "/api/v1/categories");
        validateCategoryId(patch == null ? null : patch.getId());
        String name = validateCategoryName(patch == null ? null : patch.getName());
        gate.writeLock().lock();
        try {
            LedgerCatalogRepository repo = categoryRepository();
            try (Connection connection = repo.openConnection()) {
                CategoryCommit commit = transactions.write(connection, tx -> {
                    Instant now = Instant.now(clock); String timestamp = now.toString();
                    settings.removeExpiredOperations(tx, now);
                    CategoryApiResult replay = replayCategory(tx, mutation, now);
                    if (replay != null) return new CategoryCommit(replay, -1L);
                    CategoryRecord parent = patch.getParentId() == null ? null : repo.findCategory(tx, patch.getParentId());
                    if (patch.getParentId() != null && (parent == null || parent.archivedAt != null))
                        throw categoryNotFound("parentId");
                    if (parent != null && parent.parentId != null)
                        throw categoryValidation("parentId", "父分类必须是顶级分类。");
                    int sort = repo.maxSortOrder(tx, patch.getParentId()) + 1;
                    CategoryRecord created = new CategoryRecord(patch.getId(), patch.getParentId(), name, false, false,
                            null, sort, "IMMEDIATE", null, timestamp, timestamp, 0, Collections.emptyList());
                    try { repo.insertCategory(tx, created, timestamp); }
                    catch (SQLException ex) { throw categoryReference("CATEGORY_NAME_CONFLICT"); }
                    long revision = repo.dataRevision(tx);
                    settings.advanceLedgerMeta(tx, revision, timestamp);
                    CategoryRecord stored = repo.findCategory(tx, created.id);
                    CategoryApiResult result = categorySuccess(201, singletonCategoryData(stored), revision + 1,
                            "\"0\"", "/api/v1/categories/" + created.id);
                    settings.insertOperation(tx, new LedgerOperation(mutation.getIdempotencyKey(), mutation.getHttpMethod(),
                            mutation.getCanonicalPath(), mutation.getRequestHash(), result.getStatus(), result.getResponseJson(),
                            activeContext.snapshot.getProfileId(), timestamp, now.plus(OPERATION_RETENTION).toString()));
                    return new CategoryCommit(result, revision + 1);
                });
                refreshCategoryContext(commit.dataRevision);
                return commit.result;
            }
        } catch (CategoryException ex) { throw ex;
        } catch (PersistenceException | SQLException ex) { throw categoryUnavailable();
        } catch (Exception ex) { throw categoryInternal();
        } finally { gate.writeLock().unlock(); }
    }

    @Override
    public CategoryApiResult replaceCategory(String id, long expectedRevision, CategoryPatch patch,
            CategoryMutation mutation) throws CategoryException {
        requireCategoryContext(); validateCategoryId(id);
        validateCategoryMutation(mutation, "PUT", "/api/v1/categories/" + id);
        String name = validateCategoryName(patch == null ? null : patch.getName());
        gate.writeLock().lock();
        try (Connection connection = categoryRepository().openConnection()) {
            CategoryCommit commit = transactions.write(connection, tx -> {
                Instant now = Instant.now(clock); String timestamp = now.toString();
                settings.removeExpiredOperations(tx, now);
                CategoryApiResult replay = replayCategory(tx, mutation, now);
                if (replay != null) return new CategoryCommit(replay, -1L);
                LedgerCatalogRepository repo = categoryRepository();
                CategoryRecord current = repo.findCategory(tx, id);
                if (current == null) throw categoryNotFound("id");
                if (current.system || current.archivedAt != null) throw categoryReference("CATEGORY_NOT_EDITABLE");
                requireCategoryRevision(current, expectedRevision);
                CategoryRecord parent = patch.getParentId() == null ? null : repo.findCategory(tx, patch.getParentId());
                if (patch.getParentId() != null && (parent == null || parent.archivedAt != null)) throw categoryNotFound("parentId");
                if (parent != null && parent.parentId != null) throw categoryValidation("parentId", "父分类必须是顶级分类。");
                int sort = java.util.Objects.equals(current.parentId, patch.getParentId())
                        ? current.sortOrder : repo.maxSortOrder(tx, patch.getParentId()) + 1;
                try { repo.updateCategory(tx, id, name, patch.getParentId(), sort, current.revision + 1, timestamp); }
                catch (SQLException ex) { throw categoryReference("CATEGORY_NAME_CONFLICT"); }
                long revision = repo.dataRevision(tx); settings.advanceLedgerMeta(tx, revision, timestamp);
                CategoryRecord stored = repo.findCategory(tx, id);
                CategoryApiResult result = categorySuccess(200, singletonCategoryData(stored), revision + 1,
                        "\"" + stored.revision + "\"", null);
                settings.insertOperation(tx, new LedgerOperation(mutation.getIdempotencyKey(), mutation.getHttpMethod(),
                        mutation.getCanonicalPath(), mutation.getRequestHash(), result.getStatus(), result.getResponseJson(),
                        activeContext.snapshot.getProfileId(), timestamp, now.plus(OPERATION_RETENTION).toString()));
                return new CategoryCommit(result, revision + 1);
            });
            refreshCategoryContext(commit.dataRevision); return commit.result;
        } catch (CategoryException ex) { throw ex;
        } catch (PersistenceException | SQLException ex) { throw categoryUnavailable();
        } catch (Exception ex) { throw categoryInternal();
        } finally { gate.writeLock().unlock(); }
    }

    @Override
    public CategoryApiResult archiveCategory(String id, long expectedRevision, CategoryMutation mutation)
            throws CategoryException {
        requireCategoryContext(); validateCategoryId(id);
        validateCategoryMutation(mutation, "DELETE", "/api/v1/categories/" + id);
        gate.writeLock().lock();
        try (Connection connection = categoryRepository().openConnection()) {
            CategoryCommit commit = transactions.write(connection, tx -> {
                Instant now = Instant.now(clock); String timestamp = now.toString(); LedgerCatalogRepository repo = categoryRepository();
                settings.removeExpiredOperations(tx, now); CategoryApiResult replay = replayCategory(tx, mutation, now);
                if (replay != null) return new CategoryCommit(replay, -1L);
                CategoryRecord current = repo.findCategory(tx, id);
                if (current == null) throw categoryNotFound("id");
                if (current.system || current.archivedAt != null) throw categoryReference("CATEGORY_NOT_ARCHIVABLE");
                requireCategoryRevision(current, expectedRevision);
                int children = repo.activeChildCount(tx, id);
                if (children > 0) throw new CategoryException(409, "REFERENCE_CONFLICT", "分类仍有活动子分类。",
                        Collections.emptyMap(), Collections.singletonMap("activeChildCount", children));
                repo.archiveCategory(tx, id, current.revision + 1, timestamp);
                long revision = repo.dataRevision(tx); settings.advanceLedgerMeta(tx, revision, timestamp);
                CategoryRecord stored = repo.findCategory(tx, id);
                CategoryApiResult result = categorySuccess(200, singletonCategoryData(stored), revision + 1,
                        "\"" + stored.revision + "\"", null);
                settings.insertOperation(tx, new LedgerOperation(mutation.getIdempotencyKey(), mutation.getHttpMethod(),
                        mutation.getCanonicalPath(), mutation.getRequestHash(), result.getStatus(), result.getResponseJson(),
                        activeContext.snapshot.getProfileId(), timestamp, now.plus(OPERATION_RETENTION).toString()));
                return new CategoryCommit(result, revision + 1);
            });
            refreshCategoryContext(commit.dataRevision); return commit.result;
        } catch (CategoryException ex) { throw ex;
        } catch (PersistenceException | SQLException ex) { throw categoryUnavailable();
        } catch (Exception ex) { throw categoryInternal();
        } finally { gate.writeLock().unlock(); }
    }

    @Override
    public CategoryApiResult mergeCategories(String targetId, long expectedRevision, String[] sourceIds,
            long[] sourceRevisions, CategoryMutation mutation) throws CategoryException {
        requireCategoryContext(); validateCategoryId(targetId);
        validateCategoryMutation(mutation, "POST", "/api/v1/categories/" + targetId + "/merge");
        if (sourceIds == null || sourceIds.length == 0 || sourceIds.length != sourceRevisions.length)
            throw categoryValidation("sources", "至少需要一个来源分类。");
        gate.writeLock().lock();
        try (Connection connection = categoryRepository().openConnection()) {
            CategoryCommit commit = transactions.write(connection, tx -> {
                Instant now = Instant.now(clock); String timestamp = now.toString(); LedgerCatalogRepository repo = categoryRepository();
                settings.removeExpiredOperations(tx, now); CategoryApiResult replay = replayCategory(tx, mutation, now);
                if (replay != null) return new CategoryCommit(replay, -1L);
                CategoryRecord target = repo.findCategory(tx, targetId);
                if (target == null) throw categoryNotFound("targetId");
                if (target.archivedAt != null || (target.system && repo.recordTypes(tx, targetId).isEmpty())) throw categoryReference("TARGET_NOT_RECORD_USABLE");
                requireCategoryRevision(target, expectedRevision);
                int updated = 0; List<CategoryRecord> sources = new ArrayList<>();
                java.util.HashSet<String> seen = new java.util.HashSet<>();
                for (int i=0;i<sourceIds.length;i++) {
                    if (!seen.add(sourceIds[i]) || targetId.equals(sourceIds[i])) throw categoryValidation("sources", "来源分类无效。");
                    CategoryRecord source = repo.findCategory(tx, sourceIds[i]);
                    if (source == null || source.archivedAt != null || source.system) throw categoryReference("SOURCE_NOT_MERGEABLE");
                    requireCategoryRevision(source, sourceRevisions[i]);
                    if (repo.activeChildCount(tx, source.id) > 0) throw categoryReference("ACTIVE_CHILDREN");
                    sources.add(source); updated += repo.recordCount(tx, source.id);
                }
                repo.mergeRecords(tx, targetId, sourceIds, timestamp);
                for (CategoryRecord source : sources) repo.archiveCategory(tx, source.id, source.revision + 1, timestamp);
                long revision = repo.dataRevision(tx); settings.advanceLedgerMeta(tx, revision, timestamp);
                Map<String,Object> data = new LinkedHashMap<>(); data.put("targetId", targetId); data.put("mergedIds", Arrays.asList(sourceIds)); data.put("updatedRecordCount", updated);
                CategoryApiResult result = categorySuccess(200, data, revision + 1, null, null);
                settings.insertOperation(tx, new LedgerOperation(mutation.getIdempotencyKey(), mutation.getHttpMethod(),
                        mutation.getCanonicalPath(), mutation.getRequestHash(), result.getStatus(), result.getResponseJson(),
                        activeContext.snapshot.getProfileId(), timestamp, now.plus(OPERATION_RETENTION).toString()));
                return new CategoryCommit(result, revision + 1);
            });
            refreshCategoryContext(commit.dataRevision); return commit.result;
        } catch (CategoryException ex) { throw ex;
        } catch (PersistenceException | SQLException ex) { throw categoryUnavailable();
        } catch (Exception ex) { throw categoryInternal();
        } finally { gate.writeLock().unlock(); }
    }

    @Override
    public AccountApiResult listAccounts(boolean includeArchived, LocalDate asOf, int limit, String cursor)
            throws AccountException {
        if (limit < 1 || limit > 200) throw accountValidation("limit", "必须在 1 到 200 之间。");
        if (asOf == null) asOf = businessToday();
        gate.readLock().lock();
        try { requireAccountContext(); LedgerCatalogRepository repo = accountRepository();
            try(Connection c=repo.openConnection()){List<AccountRecord> rows=repo.listAccounts(c,includeArchived,asOf);long revision=repo.dataRevision(c);int start=accountCursorStart(rows,cursor,includeArchived,asOf,revision);int end=Math.min(rows.size(),start+limit);List<Map<String,Object>> items=new ArrayList<>();for(int i=start;i<end;i++)items.add(accountMap(rows.get(i)));Map<String,Object> page=new LinkedHashMap<>();page.put("nextCursor",end<rows.size()?accountCursor(rows.get(end-1),includeArchived,asOf,revision):null);page.put("hasMore",end<rows.size());page.put("limit",limit);Map<String,Object> data=new LinkedHashMap<>();data.put("items",items);data.put("page",page);return accountSuccess(200,data,revision,"\""+revision+"\"",null);}
        } catch(AccountException ex){throw ex;} catch(PersistenceException|SQLException ex){throw accountUnavailable();} finally{gate.readLock().unlock();}
    }

    @Override
    public AccountApiResult createAccount(AccountPatch patch, AccountMutation mutation) throws AccountException {
        requireAccountContext(); validateAccountMutation(mutation,"POST","/api/v1/accounts"); if(patch==null)throw accountValidation("body","请求体必须是对象。");validateAccountId(patch.getId());validateAccountName(patch.getName());validateAccountKind(patch.getKind(),patch.isIncludeInAvailableCash());validateAccountOpeningOn(patch.getOpeningOn());
        gate.writeLock().lock(); try(Connection c=accountRepository().openConnection()){AccountCommit commit=transactions.write(c,tx->{Instant now=Instant.now(clock);String t=now.toString();LedgerCatalogRepository repo=accountRepository();settings.removeExpiredOperations(tx,now);AccountApiResult replay=replayAccount(tx,mutation,now);if(replay!=null)return new AccountCommit(replay,-1);AccountRecord a=new AccountRecord(patch.getId(),patch.getName().trim(),patch.getKind(),kindSide(patch.getKind()),patch.getOpeningOn(),patch.getOpeningBalanceMinor(),patch.isIncludeInAvailableCash(),false,null,t,t,0,patch.getOpeningBalanceMinor());try{repo.insertAccount(tx,a,t);}catch(SQLException ex){throw accountReference("ACCOUNT_NAME_CONFLICT");}long rev=repo.dataRevision(tx);settings.advanceLedgerMeta(tx,rev,t);AccountRecord stored=repo.findAccount(tx,patch.getId(),businessToday());AccountApiResult result=accountSuccess(201,singletonAccountData(stored),rev+1,"\"0\"","/api/v1/accounts/"+patch.getId());settings.insertOperation(tx,new LedgerOperation(mutation.getIdempotencyKey(),mutation.getHttpMethod(),mutation.getCanonicalPath(),mutation.getRequestHash(),result.getStatus(),result.getResponseJson(),activeContext.snapshot.getProfileId(),t,now.plus(OPERATION_RETENTION).toString()));return new AccountCommit(result,rev+1);});refreshAccountContext(commit.dataRevision);return commit.result;}catch(AccountException ex){throw ex;}catch(PersistenceException|SQLException ex){throw accountUnavailable();}catch(Exception ex){throw accountInternal();}finally{gate.writeLock().unlock();}
    }

    @Override
    public AccountApiResult replaceAccount(String id,long expectedRevision,AccountPatch patch,AccountMutation mutation)throws AccountException{
        requireAccountContext();validateAccountId(id);validateAccountMutation(mutation,"PUT","/api/v1/accounts/"+id);if(patch==null)throw accountValidation("body","请求体必须是对象。");validateAccountName(patch.getName());validateAccountKind(patch.getKind(),patch.isIncludeInAvailableCash());validateAccountOpeningOn(patch.getOpeningOn());
        gate.writeLock().lock();try(Connection c=accountRepository().openConnection()){AccountCommit commit=transactions.write(c,tx->{Instant now=Instant.now(clock);String t=now.toString();LedgerCatalogRepository repo=accountRepository();settings.removeExpiredOperations(tx,now);AccountApiResult replay=replayAccount(tx,mutation,now);if(replay!=null)return new AccountCommit(replay,-1);AccountRecord current=repo.findAccount(tx,id,businessToday());if(current==null)throw accountNotFound();if(current.archivedAt!=null)throw accountReference("ACCOUNT_NOT_ACTIVE");requireAccountRevision(current,expectedRevision);String earliest=repo.earliestSettlement(tx,id);if(earliest!=null&&patch.getOpeningOn().toString().compareTo(earliest)>0)throw new AccountException(409,"REFERENCE_CONFLICT","期初日不能晚于已有结算日。",Collections.emptyMap(),Collections.singletonMap("earliestSettlementOn",earliest));AccountRecord replacement=new AccountRecord(id,patch.getName().trim(),patch.getKind(),kindSide(patch.getKind()),patch.getOpeningOn(),patch.getOpeningBalanceMinor(),patch.isIncludeInAvailableCash(),current.system,current.archivedAt,current.createdAt,t,current.revision+1,patch.getOpeningBalanceMinor());try{repo.updateAccount(tx,id,replacement,current.revision+1,t);}catch(SQLException ex){throw accountReference("ACCOUNT_NAME_CONFLICT");}long rev=repo.dataRevision(tx);settings.advanceLedgerMeta(tx,rev,t);AccountRecord stored=repo.findAccount(tx,id,businessToday());AccountApiResult result=accountSuccess(200,singletonAccountData(stored),rev+1,"\""+stored.revision+"\"",null);settings.insertOperation(tx,new LedgerOperation(mutation.getIdempotencyKey(),mutation.getHttpMethod(),mutation.getCanonicalPath(),mutation.getRequestHash(),result.getStatus(),result.getResponseJson(),activeContext.snapshot.getProfileId(),t,now.plus(OPERATION_RETENTION).toString()));return new AccountCommit(result,rev+1);});refreshAccountContext(commit.dataRevision);return commit.result;}catch(AccountException ex){throw ex;}catch(PersistenceException|SQLException ex){throw accountUnavailable();}catch(Exception ex){throw accountInternal();}finally{gate.writeLock().unlock();}
    }

    @Override
    public AccountApiResult archiveAccount(String id,long expectedRevision,AccountMutation mutation)throws AccountException{
        requireAccountContext();validateAccountId(id);validateAccountMutation(mutation,"DELETE","/api/v1/accounts/"+id);gate.writeLock().lock();try(Connection c=accountRepository().openConnection()){AccountCommit commit=transactions.write(c,tx->{Instant now=Instant.now(clock);String t=now.toString();LedgerCatalogRepository repo=accountRepository();settings.removeExpiredOperations(tx,now);AccountApiResult replay=replayAccount(tx,mutation,now);if(replay!=null)return new AccountCommit(replay,-1);AccountRecord current=repo.findAccount(tx,id,businessToday());if(current==null)throw accountNotFound();if(current.system||current.archivedAt!=null)throw accountReference("ACCOUNT_NOT_ARCHIVABLE");requireAccountRevision(current,expectedRevision);repo.archiveAccount(tx,id,current.revision+1,t);long rev=repo.dataRevision(tx);settings.advanceLedgerMeta(tx,rev,t);AccountRecord stored=repo.findAccount(tx,id,businessToday());AccountApiResult result=accountSuccess(200,singletonAccountData(stored),rev+1,"\""+stored.revision+"\"",null);settings.insertOperation(tx,new LedgerOperation(mutation.getIdempotencyKey(),mutation.getHttpMethod(),mutation.getCanonicalPath(),mutation.getRequestHash(),result.getStatus(),result.getResponseJson(),activeContext.snapshot.getProfileId(),t,now.plus(OPERATION_RETENTION).toString()));return new AccountCommit(result,rev+1);});refreshAccountContext(commit.dataRevision);return commit.result;}catch(AccountException ex){throw ex;}catch(PersistenceException|SQLException ex){throw accountUnavailable();}catch(Exception ex){throw accountInternal();}finally{gate.writeLock().unlock();}
    }

    @Override
    public RecordApiResult listRecords(String status, int limit, String cursor) throws RecordException {
        return listRecords(RecordQuery.basic(status, limit, cursor));
    }

    @Override
    public RecordApiResult listRecords(RecordQuery query) throws RecordException {
        validateRecordQuery(query);
        gate.readLock().lock();
        try { requireRecordContext(); LedgerCatalogRepository repo=recordRepository();
            try(Connection c=repo.openConnection()){List<RecordRecord> rows=repo.listRecords(c,query);long revision=repo.dataRevision(c);int start=recordCursorStart(rows,query,revision);int end=Math.min(rows.size(),start+query.getLimit());List<Map<String,Object>> items=new ArrayList<>();for(int i=start;i<end;i++)items.add(recordMap(rows.get(i)));Map<String,Object> page=new LinkedHashMap<>();page.put("nextCursor",end<rows.size()?recordCursor(rows.get(end-1),query,revision):null);page.put("hasMore",end<rows.size());page.put("limit",query.getLimit());Map<String,Object> data=new LinkedHashMap<>();data.put("items",items);data.put("page",page);return recordSuccess(200,data,revision,null,null);}
        } catch(RecordException ex){throw ex;} catch(PersistenceException|SQLException ex){throw recordUnavailable();} finally{gate.readLock().unlock();}
    }

    @Override
    public RecordApiResult getRecord(String id) throws RecordException {
        return getRecord(id, "ACTIVE");
    }

    @Override
    public RecordApiResult getRecord(String id, String status) throws RecordException {
        requireRecordContext(); validateRecordId(id); gate.readLock().lock();
        if (!"ACTIVE".equals(status) && !"TRASHED".equals(status)) throw recordValidation("status", "必须是 ACTIVE 或 TRASHED。");
        try { LedgerCatalogRepository repo=recordRepository(); try(Connection c=repo.openConnection()){RecordRecord record=repo.findRecord(c,id);if(record==null||!isBasicRecord(record.recordType)||("TRASHED".equals(status)) != (record.deletedAt != null))throw recordNotFound();return recordSuccess(200,Collections.singletonMap("record",recordMap(record)),repo.dataRevision(c),"\""+record.revision+"\"",null);} }
        catch(RecordException ex){throw ex;}catch(PersistenceException|SQLException ex){throw recordUnavailable();}finally{gate.readLock().unlock();}
    }

    @Override
    public RecordApiResult createRecord(RecordPatch patch, RecordMutation mutation) throws RecordException {
        requireRecordContext(); validateRecordMutation(mutation,"POST","/api/v1/records"); validateRecordPatch(patch,true);
        gate.writeLock().lock(); try(Connection c=recordRepository().openConnection()){RecordCommit commit=transactions.write(c,tx->{Instant now=Instant.now(clock);String t=now.toString();LedgerCatalogRepository repo=recordRepository();settings.removeExpiredOperations(tx,now);RecordApiResult replay=replayRecord(tx,mutation,now);if(replay!=null)return new RecordCommit(replay,-1);validateRecordReferences(tx,patch);RecordRecord row=new RecordRecord(patch.getId(),patch.getOccurredOn().toString(),patch.getType().name(),patch.getAmountMinor(),patch.getCategoryId(),patch.getAccountId(),"PAID_FROM_ACCOUNT",patch.getSettlementOn().toString(),patch.getNote(),t,t,null,0,null,null,null,null);try{repo.insertRecord(tx,row,t);}catch(SQLException ex){throw recordReference("RECORD_ID_OR_REFERENCE_CONFLICT");}long revision=repo.dataRevision(tx);settings.advanceLedgerMeta(tx,revision,t);RecordApiResult result=recordSuccess(201,Collections.singletonMap("record",recordMap(repo.findRecord(tx,row.id))),revision+1,"\"0\"","/api/v1/records/"+row.id);insertRecordOperation(tx,mutation,result,t,now);return new RecordCommit(result,revision+1);});refreshRecordContext(commit.dataRevision);return commit.result;}catch(RecordException ex){throw ex;}catch(PersistenceException|SQLException ex){throw recordUnavailable();}catch(Exception ex){throw recordInternal();}finally{gate.writeLock().unlock();}
    }

    @Override
    public RecordApiResult replaceRecord(String id,long expectedRevision,RecordPatch patch,RecordMutation mutation)throws RecordException{
        requireRecordContext();validateRecordId(id);validateRecordMutation(mutation,"PUT","/api/v1/records/"+id);validateRecordPatch(patch,false);gate.writeLock().lock();try(Connection c=recordRepository().openConnection()){RecordCommit commit=transactions.write(c,tx->{Instant now=Instant.now(clock);String t=now.toString();LedgerCatalogRepository repo=recordRepository();settings.removeExpiredOperations(tx,now);RecordApiResult replay=replayRecord(tx,mutation,now);if(replay!=null)return new RecordCommit(replay,-1);RecordRecord current=repo.findRecord(tx,id);if(current==null||!isBasicRecord(current.recordType))throw recordNotFound();if(current.deletedAt!=null)throw recordReference("RECORD_NOT_ACTIVE");if(current.revision!=expectedRevision)throw recordRevision(current.revision);validateRecordReferences(tx,patch);RecordRecord next=new RecordRecord(id,patch.getOccurredOn().toString(),patch.getType().name(),patch.getAmountMinor(),patch.getCategoryId(),patch.getAccountId(),"PAID_FROM_ACCOUNT",patch.getSettlementOn().toString(),patch.getNote(),current.createdAt,t,null,current.revision+1,null,null,null,null);repo.updateRecord(tx,id,next,current.revision+1,t);long revision=repo.dataRevision(tx);settings.advanceLedgerMeta(tx,revision,t);RecordApiResult result=recordSuccess(200,Collections.singletonMap("record",recordMap(repo.findRecord(tx,id))),revision+1,"\""+(current.revision+1)+"\"",null);insertRecordOperation(tx,mutation,result,t,now);return new RecordCommit(result,revision+1);});refreshRecordContext(commit.dataRevision);return commit.result;}catch(RecordException ex){throw ex;}catch(PersistenceException|SQLException ex){throw recordUnavailable();}catch(Exception ex){throw recordInternal();}finally{gate.writeLock().unlock();}
    }

    @Override
    public RecordApiResult trashRecord(String id,long expectedRevision,RecordMutation mutation)throws RecordException{ return changeRecordStatus(id,expectedRevision,mutation,false); }

    @Override
    public RecordApiResult restoreRecord(String id,long expectedRevision,RecordMutation mutation)throws RecordException{ return changeRecordStatus(id,expectedRevision,mutation,true); }

    private RecordApiResult changeRecordStatus(String id,long expectedRevision,RecordMutation mutation,boolean restore)throws RecordException{
        requireRecordContext();validateRecordId(id);String method=restore?"POST":"DELETE";String path="/api/v1/records/"+id+(restore?"/restore":"");validateRecordMutation(mutation,method,path);gate.writeLock().lock();try(Connection c=recordRepository().openConnection()){RecordCommit commit=transactions.write(c,tx->{Instant now=Instant.now(clock);String t=now.toString();LedgerCatalogRepository repo=recordRepository();settings.removeExpiredOperations(tx,now);RecordApiResult replay=replayRecord(tx,mutation,now);if(replay!=null)return new RecordCommit(replay,-1);RecordRecord current=repo.findRecord(tx,id);if(current==null||!isBasicRecord(current.recordType))throw recordNotFound();if(current.revision!=expectedRevision)throw recordRevision(current.revision);if(restore&&current.deletedAt==null)throw recordReference("RECORD_NOT_TRASHED");if(!restore&&current.deletedAt!=null)throw recordReference("RECORD_NOT_ACTIVE");if(restore)validateRecordReferences(tx,new RecordPatch(id,RecordType.valueOf(current.recordType),current.amountMinor,LocalDate.parse(current.occurredOn),current.categoryId,current.accountId,LocalDate.parse(current.settlementOn),current.note));repo.setRecordDeleted(tx,id,restore?null:t,current.revision+1,t);long revision=repo.dataRevision(tx);settings.advanceLedgerMeta(tx,revision,t);RecordRecord stored=repo.findRecord(tx,id);RecordApiResult result=recordSuccess(200,Collections.singletonMap("record",recordMap(stored)),revision+1,"\""+stored.revision+"\"",null);insertRecordOperation(tx,mutation,result,t,now);return new RecordCommit(result,revision+1);});refreshRecordContext(commit.dataRevision);return commit.result;}catch(RecordException ex){throw ex;}catch(PersistenceException|SQLException ex){throw recordUnavailable();}catch(Exception ex){throw recordInternal();}finally{gate.writeLock().unlock();}
    }

    @Override
    public ProfileApiResult findOperation(String idempotencyKey) throws ProfileException {
        requireMutableContext();
        validateIdempotencyKey(idempotencyKey);
        gate.readLock().lock();
        try (Connection connection = catalog.openConnection()) {
            Instant now = Instant.now(clock);
            CatalogOperation catalogOperation = catalog.findCatalogOperation(connection, idempotencyKey, now);
            CatalogOperation ledgerOperation = findLedgerOperation(activeContext.ledgerFile, idempotencyKey, now);
            if (catalogOperation != null && ledgerOperation != null) {
                throw new ProfileException(500, "INTERNAL_ERROR", "操作记录完整性异常。");
            }
            CatalogOperation operation = catalogOperation != null ? catalogOperation : ledgerOperation;
            if (operation == null) {
                throw new ProfileException(404, "NOT_FOUND", "未找到已提交的操作。");
            }
            JsonNode stored = objectMapper.readTree(operation.getResponseJson());
            JsonNode resultData = stored.get("data");
            JsonNode storedMeta = stored.get("meta");
            Map<String, Object> operationData = new LinkedHashMap<>();
            operationData.put("idempotencyKey", operation.getIdempotencyKey());
            operationData.put("status", "COMPLETED");
            operationData.put("responseStatus", operation.getResponseStatus());
            operationData.put("result", resultData == null ? Collections.emptyMap() : resultData);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", operationData);
            body.put("meta", storedMeta == null ? meta(activeContext.snapshot.getDataRevision(), null) : storedMeta);
            return new ProfileApiResult(200, objectMapper.writeValueAsString(body), null, null);
        } catch (ProfileException ex) {
            throw ex;
        } catch (PersistenceException | SQLException ex) {
            throw unavailable(ex);
        } catch (IOException ex) {
            throw internal(ex);
        } finally {
            gate.readLock().unlock();
        }
    }

    private ProfileApiResult replayOrConflict(Connection connection, ProfileMutation mutation, Instant now)
            throws ProfileException, PersistenceException {
        CatalogOperation operation = catalog.findCatalogOperation(connection, mutation.getIdempotencyKey(), now);
        if (operation != null) {
            if (sameRequest(operation, mutation)) {
                return new ProfileApiResult(operation.getResponseStatus(), operation.getResponseJson(),
                        operation.getEtag(), operation.getLocation());
            }
            throw idempotencyConflict();
        }
        if (findLedgerOperation(activeContext.ledgerFile, mutation.getIdempotencyKey(), now) != null) {
            throw idempotencyConflict();
        }
        return null;
    }

    private CatalogOperation findLedgerOperation(Path ledgerFile, String key, Instant now) throws PersistenceException {
        try (Connection connection = new SqliteDatabase(ledgerFile).open();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT idempotency_key, http_method, canonical_path, request_hash, response_status, "
                                + "response_json, NULL AS etag, NULL AS location, completed_at, expires_at "
                                + "FROM processed_operation WHERE idempotency_key = ? AND expires_at > ?")) {
            statement.setString(1, key);
            statement.setString(2, now.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new CatalogOperation(result.getString("idempotency_key"), result.getString("http_method"),
                        result.getString("canonical_path"), result.getString("request_hash"),
                        result.getInt("response_status"), result.getString("response_json"), null, null,
                        result.getString("completed_at"), result.getString("expires_at"));
            }
        } catch (IOException | SQLException ex) {
            throw new PersistenceException("ledger operation could not be read", ex);
        }
    }

    private SettingsApiResult replaySettings(LedgerOperation operation) throws SettingsException {
        try {
            JsonNode stored = objectMapper.readTree(operation.getResponseJson());
            JsonNode revision = stored == null ? null : stored.at("/data/revision");
            if (revision == null || !revision.isIntegralNumber() || !revision.canConvertToLong()
                    || revision.asLong() < 0L) {
                throw settingsInternal();
            }
            return new SettingsApiResult(operation.getResponseStatus(), operation.getResponseJson(),
                    "\"" + revision.asLong() + "\"");
        } catch (IOException ex) {
            throw settingsInternal();
        }
    }

    private SettingsApiResult settingsSuccess(SettingsState state) throws SettingsException {
        Map<String, Object> currency = new LinkedHashMap<>();
        currency.put("code", state.getSettings().getCurrencyCode());
        currency.put("symbol", state.getSettings().getCurrencySymbol());
        Map<String, Object> safetyBuffer = new LinkedHashMap<>();
        safetyBuffer.put("amount", formatMoney(state.getSettings().getSafetyBufferMinor()));
        safetyBuffer.put("currency", state.getSettings().getCurrencyCode());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notificationsEnabled", state.getSettings().isNotificationsEnabled());
        data.put("autoBackupEnabled", state.getSettings().isAutoBackupEnabled());
        data.put("autoBackupIntervalDays", state.getSettings().getAutoBackupIntervalDays());
        data.put("autoBackupRetentionCount", state.getSettings().getAutoBackupRetentionCount());
        data.put("lastAutoBackupAt", state.getSettings().getLastAutoBackupAt());
        data.put("lastSettingsSection", state.getSettings().getLastSettingsSection());
        data.put("currency", currency);
        data.put("safetyBuffer", safetyBuffer);
        data.put("hideAllAmounts", state.getSettings().isHideAllAmounts());
        data.put("themeName", state.getSettings().getThemeName());
        data.put("revision", state.getSettings().getRevision());
        data.put("updatedAt", state.getSettings().getUpdatedAt());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("dataRevision", state.getDataRevision());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("meta", meta);
        try {
            return new SettingsApiResult(200, objectMapper.writeValueAsString(body),
                    "\"" + state.getSettings().getRevision() + "\"");
        } catch (JsonProcessingException ex) {
            throw settingsInternal();
        }
    }

    private SettingsRecord applyPatch(SettingsRecord current, SettingsPatch patch, String updatedAt) {
        boolean notificationsEnabled = patch.getNotificationsEnabled() == null
                ? current.isNotificationsEnabled() : patch.getNotificationsEnabled();
        boolean autoBackupEnabled = patch.getAutoBackupEnabled() == null
                ? current.isAutoBackupEnabled() : patch.getAutoBackupEnabled();
        int autoBackupIntervalDays = patch.getAutoBackupIntervalDays() == null
                ? current.getAutoBackupIntervalDays() : patch.getAutoBackupIntervalDays();
        int autoBackupRetentionCount = patch.getAutoBackupRetentionCount() == null
                ? current.getAutoBackupRetentionCount() : patch.getAutoBackupRetentionCount();
        String lastSettingsSection = patch.getLastSettingsSection() == null
                ? current.getLastSettingsSection() : patch.getLastSettingsSection();
        long safetyBufferMinor = patch.getSafetyBufferMinor() == null
                ? current.getSafetyBufferMinor() : patch.getSafetyBufferMinor();
        boolean hideAllAmounts = patch.getHideAllAmounts() == null
                ? current.isHideAllAmounts() : patch.getHideAllAmounts();
        String themeName = patch.getThemeName() == null ? current.getThemeName() : patch.getThemeName();
        String customThemeCss = patch.getThemeName() == null ? current.getCustomThemeCss() : null;
        return new SettingsRecord(notificationsEnabled, autoBackupEnabled, autoBackupIntervalDays,
                autoBackupRetentionCount, current.getLastAutoBackupAt(), lastSettingsSection,
                current.getCurrencyCode(), current.getCurrencySymbol(), safetyBufferMinor, hideAllAmounts,
                themeName, customThemeCss, current.getRevision() + 1, updatedAt);
    }

    private static boolean sameSettings(SettingsRecord left, SettingsRecord right) {
        return left.isNotificationsEnabled() == right.isNotificationsEnabled()
                && left.isAutoBackupEnabled() == right.isAutoBackupEnabled()
                && left.getAutoBackupIntervalDays() == right.getAutoBackupIntervalDays()
                && left.getAutoBackupRetentionCount() == right.getAutoBackupRetentionCount()
                && sameNullable(left.getLastAutoBackupAt(), right.getLastAutoBackupAt())
                && sameNullable(left.getLastSettingsSection(), right.getLastSettingsSection())
                && sameNullable(left.getCurrencyCode(), right.getCurrencyCode())
                && sameNullable(left.getCurrencySymbol(), right.getCurrencySymbol())
                && left.getSafetyBufferMinor() == right.getSafetyBufferMinor()
                && left.isHideAllAmounts() == right.isHideAllAmounts()
                && sameNullable(left.getThemeName(), right.getThemeName())
                && sameNullable(left.getCustomThemeCss(), right.getCustomThemeCss());
    }

    private static boolean sameNullable(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static String formatMoney(long minor) {
        return BigDecimal.valueOf(minor, 2).setScale(2).toPlainString();
    }

    private static boolean sameSettingsRequest(LedgerOperation operation, SettingsMutation mutation) {
        return operation.getHttpMethod().equals(mutation.getHttpMethod())
                && operation.getCanonicalPath().equals(mutation.getCanonicalPath())
                && operation.getRequestHash().equals(mutation.getRequestHash());
    }

    private static LedgerOperation settingsOperation(SettingsMutation mutation, SettingsApiResult result,
            String profileId, Instant now) {
        return new LedgerOperation(mutation.getIdempotencyKey(), mutation.getHttpMethod(), mutation.getCanonicalPath(),
                mutation.getRequestHash(), result.getStatus(), result.getResponseJson(), profileId,
                now.toString(), now.plus(OPERATION_RETENTION).toString());
    }

    private BootstrapSnapshot validateTargetLedger(CatalogProfile target) throws ProfileException {
        try {
            Path directory = profileBootstrap.resolveProfileDirectory(target.getId(), target.getRelativeDirectory());
            Path ledgerFile = directory.resolve("ledger.db").normalize();
            if (!Files.isRegularFile(ledgerFile, LinkOption.NOFOLLOW_LINKS)) {
                throw targetUnavailable();
            }
            return ledgerBootstrap.open(ledgerFile, target.getId());
        } catch (PersistenceException ex) {
            throw targetUnavailable();
        }
    }

    private List<ProfileView> profileViews(CatalogState state, boolean includeArchived) {
        List<ProfileView> views = new ArrayList<>();
        for (CatalogProfile profile : state.getProfiles()) {
            String status = profile.getArchivedAt() != null ? "ARCHIVED"
                    : profile.getId().equals(state.getActiveProfileId()) ? "ACTIVE" : "INACTIVE";
            if (includeArchived || !"ARCHIVED".equals(status)) {
                views.add(new ProfileView(profile, status));
            }
        }
        views.sort(Comparator.comparingInt(ProfileView::sortRank)
                .thenComparing(ProfileView::getLastOpenedAt, Comparator.reverseOrder())
                .thenComparing(ProfileView::getId));
        return views;
    }

    private int startAfterCursor(List<ProfileView> views, Cursor cursor) throws ProfileException {
        if (cursor == null) {
            return 0;
        }
        for (int index = 0; index < views.size(); index++) {
            ProfileView view = views.get(index);
            if (view.sortRank() == cursor.sortRank && view.getLastOpenedAt().equals(cursor.lastOpenedAt)
                    && view.getId().equals(cursor.id)) {
                return index + 1;
            }
        }
        throw validation("cursor", "游标已失效。");
    }

    private CatalogProfile requireAvailableProfile(CatalogState state, String id) throws ProfileException {
        CatalogProfile target = state.findProfile(id);
        if (target == null || target.getArchivedAt() != null) {
            throw new ProfileException(404, "NOT_FOUND", "用户空间不存在。");
        }
        return target;
    }

    private void requireActiveContext(CatalogState state) throws ProfileException {
        if (!state.getActiveProfileId().equals(activeContext.snapshot.getProfileId())) {
            throw new ProfileException(500, "INTERNAL_ERROR", "活动用户空间状态不一致。");
        }
    }

    private void requireMutableContext() throws ProfileException {
        if (activeContext == null) {
            throw new ProfileException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能修改或查询操作。");
        }
    }

    private boolean setupComplete() {
        return activeContext != null && "COMPLETED".equals(activeContext.snapshot.getSetupState());
    }

    private void requireSettingsContext() throws SettingsException {
        if (activeContext == null) {
            throw new SettingsException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能读取或保存设置。");
        }
        if (!setupComplete()) throw new SettingsException(409, "LEDGER_SETUP_REQUIRED", "请先完成账本初始化。");
    }

    private static void requireRevision(CatalogProfile profile, long expectedRevision) throws ProfileException {
        if (profile.getRevision() != expectedRevision) {
            throw new ProfileException(409, "REVISION_CONFLICT", "用户空间已被更新。", Collections.emptyMap(),
                    Collections.singletonMap("currentRevision", profile.getRevision()));
        }
    }

    private static String validateName(String name) throws ProfileException {
        if (name == null) {
            throw validation("name", "名称不能为空。");
        }
        String trimmed = name.trim();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < 1 || length > 100) {
            throw validation("name", "名称长度必须为 1 到 100 个字符。");
        }
        return trimmed;
    }

    private static void validateProfileId(String id) throws ProfileException {
        if (!isLowercaseUuid(id)) {
            throw validation("id", "必须是小写 UUID。");
        }
    }

    private static void validateIdempotencyKey(String key) throws ProfileException {
        if (!isLowercaseUuid(key)) {
            throw validation("Idempotency-Key", "必须是小写 UUID。");
        }
    }

    private static void validateMutation(ProfileMutation mutation, String method, String path) throws ProfileException {
        if (mutation == null || !method.equals(mutation.getHttpMethod()) || !path.equals(mutation.getCanonicalPath())
                || mutation.getRequestHash() == null || !mutation.getRequestHash().matches("[0-9a-f]{64}")) {
            throw validation("request", "请求幂等信息无效。");
        }
        validateIdempotencyKey(mutation.getIdempotencyKey());
    }

    private static void validateSettingsRevision(long revision) throws SettingsException {
        if (revision < 0L) {
            throw settingsValidation("If-Match", "必须是非负版本。");
        }
    }

    private LedgerCatalogRepository categoryRepository() throws CategoryException {
        requireCategoryContext();
        return new LedgerCatalogRepository(activeContext.ledgerFile);
    }

    private void requireCategoryContext() throws CategoryException {
        if (activeContext == null) throw new CategoryException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能操作分类。");
        if (!setupComplete()) throw new CategoryException(409, "LEDGER_SETUP_REQUIRED", "请先完成账本初始化。");
    }

    private void refreshCategoryContext(long dataRevision) {
        if (dataRevision < 0 || activeContext == null) return;
        activeContext = new ActiveProfileContext(
                new BootstrapSnapshot(activeContext.snapshot.getProfileId(), activeContext.snapshot.getSchemaVersion(),
                        dataRevision, activeContext.snapshot.getSetupState(), activeContext.snapshot.getLedgerStartOn()),
                activeContext.ledgerFile);
    }

    private CategoryApiResult replayCategory(Connection connection, CategoryMutation mutation, Instant now)
            throws CategoryException, PersistenceException {
        LedgerOperation operation = settings.findOperation(connection, mutation.getIdempotencyKey(), now);
        if (operation == null) return null;
        if (!operation.getHttpMethod().equals(mutation.getHttpMethod())
                || !operation.getCanonicalPath().equals(mutation.getCanonicalPath())
                || !operation.getRequestHash().equals(mutation.getRequestHash())) {
            throw new CategoryException(409, "IDEMPOTENCY_CONFLICT", "幂等键已用于不同请求。");
        }
        return new CategoryApiResult(operation.getResponseStatus(), operation.getResponseJson(), null, null);
    }

    private CategoryApiResult categorySuccess(int status, Map<String, Object> data, long dataRevision,
            String etag, String location) throws CategoryException {
        Map<String, Object> body = new LinkedHashMap<>(); body.put("data", data);
        Map<String, Object> meta = new LinkedHashMap<>(); meta.put("dataRevision", dataRevision); body.put("meta", meta);
        try { return new CategoryApiResult(status, objectMapper.writeValueAsString(body), etag, location); }
        catch (JsonProcessingException ex) { throw categoryInternal(); }
    }

    private static Map<String, Object> singletonCategoryData(CategoryRecord category) {
        Map<String, Object> data = new LinkedHashMap<>(); data.put("category", categoryMap(category)); return data;
    }

    private static Map<String, Object> categoryMap(CategoryRecord category) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", category.id); value.put("parentId", category.parentId); value.put("name", category.name);
        value.put("status", category.archivedAt == null ? "ACTIVE" : "ARCHIVED"); value.put("isSystem", category.system);
        value.put("isLegacyCustom", category.legacyCustom); value.put("sortOrder", category.sortOrder);
        value.put("defaultRecognitionMethod", category.defaultRecognitionMethod);
        value.put("recommendedDepreciationMethod", category.recommendedDepreciationMethod);
        value.put("recordTypes", category.recordTypes);
        value.put("canUseForRecords", !category.system ? category.archivedAt == null : !category.recordTypes.isEmpty() && category.archivedAt == null);
        value.put("createdAt", category.createdAt); value.put("updatedAt", category.updatedAt); value.put("revision", category.revision);
        return value;
    }

    private static int categoryCursorStart(List<CategoryRecord> rows, String cursor, boolean includeArchived,
            long dataRevision) throws CategoryException {
        if (cursor == null) return 0;
        try {
            String[] f = new String(java.util.Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\n", -1);
            if (f.length != 4 || !"v1".equals(f[0]) || !Boolean.toString(includeArchived).equals(f[1])
                    || Long.parseLong(f[2]) != dataRevision) throw new IllegalArgumentException();
            for (int i=0;i<rows.size();i++) if (rows.get(i).id.equals(f[3])) return i + 1;
            throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) { throw categoryValidation("cursor", "游标无效或已过期。"); }
    }

    private static String categoryCursor(CategoryRecord row, boolean includeArchived, long dataRevision) {
        String value = "v1\n" + includeArchived + "\n" + dataRevision + "\n" + row.id;
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void validateCategoryMutation(CategoryMutation mutation, String method, String path)
            throws CategoryException {
        if (mutation == null || !method.equals(mutation.getHttpMethod()) || !path.equals(mutation.getCanonicalPath())
                || mutation.getRequestHash() == null || !mutation.getRequestHash().matches("[0-9a-f]{64}")
                || !isLowercaseUuid(mutation.getIdempotencyKey()))
            throw categoryValidation("request", "请求幂等信息无效。");
    }

    private static void validateCategoryId(String id) throws CategoryException {
        if (!isLowercaseUuid(id)) throw categoryValidation("id", "必须是小写 UUID。");
    }

    private static String validateCategoryName(String name) throws CategoryException {
        if (name == null) throw categoryValidation("name", "名称不能为空。");
        String value = name.trim(); int length = value.codePointCount(0, value.length());
        if (length < 1 || length > 100) throw categoryValidation("name", "名称长度必须为 1 到 100 个字符。");
        return value;
    }

    private static void requireCategoryRevision(CategoryRecord category, long expected) throws CategoryException {
        if (category.revision != expected)
            throw new CategoryException(409, "REVISION_CONFLICT", "分类已被更新。", Collections.emptyMap(),
                    Collections.singletonMap("currentRevision", category.revision));
    }

    private static CategoryException categoryValidation(String field, String message) {
        return new CategoryException(400, "VALIDATION_FAILED", "请求参数无效。",
                Collections.singletonMap(field, message), Collections.emptyMap());
    }

    private static CategoryException categoryNotFound(String field) {
        return new CategoryException(404, "NOT_FOUND", "分类不存在。",
                Collections.singletonMap(field, "分类不存在。"), Collections.emptyMap());
    }

    private static CategoryException categoryReference(String reason) {
        return new CategoryException(409, "REFERENCE_CONFLICT", "分类状态冲突。", Collections.emptyMap(),
                Collections.singletonMap("reason", reason));
    }

    private static CategoryException categoryUnavailable() {
        return new CategoryException(503, "SERVICE_UNAVAILABLE", "本地数据暂时不可用。");
    }

    private static CategoryException categoryInternal() {
        return new CategoryException(500, "INTERNAL_ERROR", "本地服务发生内部错误。");
    }

    private LedgerCatalogRepository accountRepository() throws AccountException {
        requireAccountContext(); return new LedgerCatalogRepository(activeContext.ledgerFile);
    }
    private void requireAccountContext() throws AccountException { if(activeContext==null)throw new AccountException(423,"RECOVERY_REQUIRED","数据恢复完成前不能操作账户。"); if(!setupComplete())throw new AccountException(409,"LEDGER_SETUP_REQUIRED","请先完成账本初始化。"); }
    private void refreshAccountContext(long revision){if(revision<0||activeContext==null)return;activeContext=new ActiveProfileContext(new BootstrapSnapshot(activeContext.snapshot.getProfileId(),activeContext.snapshot.getSchemaVersion(),revision,activeContext.snapshot.getSetupState(),activeContext.snapshot.getLedgerStartOn()),activeContext.ledgerFile);}
    private AccountApiResult replayAccount(Connection c,AccountMutation mutation,Instant now)throws AccountException,PersistenceException{LedgerOperation op=settings.findOperation(c,mutation.getIdempotencyKey(),now);if(op==null)return null;if(!op.getHttpMethod().equals(mutation.getHttpMethod())||!op.getCanonicalPath().equals(mutation.getCanonicalPath())||!op.getRequestHash().equals(mutation.getRequestHash()))throw new AccountException(409,"IDEMPOTENCY_CONFLICT","幂等键已用于不同请求。");return new AccountApiResult(op.getResponseStatus(),op.getResponseJson(),null,null);}
    private AccountApiResult accountSuccess(int status,Map<String,Object> data,long revision,String etag,String location)throws AccountException{Map<String,Object> body=new LinkedHashMap<>();body.put("data",data);Map<String,Object> meta=new LinkedHashMap<>();meta.put("dataRevision",revision);body.put("meta",meta);try{return new AccountApiResult(status,objectMapper.writeValueAsString(body),etag,location);}catch(JsonProcessingException ex){throw accountInternal();}}
    private static Map<String,Object> singletonAccountData(AccountRecord a){Map<String,Object> d=new LinkedHashMap<>();d.put("account",accountMap(a));return d;}
    private static Map<String,Object> accountMap(AccountRecord a){Map<String,Object> d=new LinkedHashMap<>();d.put("id",a.id);d.put("name",a.name);d.put("kind",a.kind);d.put("balanceSide",a.balanceSide);d.put("openingOn",a.openingOn.toString());d.put("openingBalance",formatMoney(a.openingBalanceMinor));d.put("balance",formatMoney(a.balanceMinor));d.put("currency","CNY");d.put("includeInAvailableCash",a.includeInAvailableCash);d.put("isSystem",a.system);d.put("status",a.archivedAt==null?"ACTIVE":"ARCHIVED");d.put("createdAt",a.createdAt);d.put("updatedAt",a.updatedAt);d.put("revision",a.revision);return d;}
    private static int accountCursorStart(List<AccountRecord> rows,String cursor,boolean includeArchived,LocalDate asOf,long revision)throws AccountException{if(cursor==null)return 0;try{String[] f=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\n",-1);if(f.length!=5||!"v1".equals(f[0])||!Boolean.toString(includeArchived).equals(f[1])||!asOf.toString().equals(f[2])||Long.parseLong(f[3])!=revision)throw new IllegalArgumentException();for(int i=0;i<rows.size();i++)if(rows.get(i).id.equals(f[4]))return i+1;throw new IllegalArgumentException();}catch(IllegalArgumentException ex){throw accountValidation("cursor","游标无效或已过期。");}}
    private static String accountCursor(AccountRecord a,boolean includeArchived,LocalDate asOf,long revision){String s="v1\n"+includeArchived+"\n"+asOf+"\n"+revision+"\n"+a.id;return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
    private static void validateAccountMutation(AccountMutation m,String method,String path)throws AccountException{if(m==null||!method.equals(m.getHttpMethod())||!path.equals(m.getCanonicalPath())||m.getRequestHash()==null||!m.getRequestHash().matches("[0-9a-f]{64}")||!isLowercaseUuid(m.getIdempotencyKey()))throw accountValidation("request","请求幂等信息无效。");}
    private static void validateAccountId(String id)throws AccountException{if(!isLowercaseUuid(id))throw accountValidation("id","必须是小写 UUID。");}
    private static void validateAccountName(String name)throws AccountException{if(name==null||name.trim().isEmpty()||name.trim().codePointCount(0,name.trim().length())>100)throw accountValidation("name","名称长度必须为 1 到 100 个字符。");}
    private static void validateAccountKind(String kind,boolean include)throws AccountException{List<String> kinds=Arrays.asList("CASH","BANK","WALLET","CREDIT","LOAN","OTHER_ASSET","OTHER_LIABILITY");if(!kinds.contains(kind))throw accountValidation("kind","账户类型无效。");if(include&&("CREDIT".equals(kind)||"LOAN".equals(kind)||"OTHER_LIABILITY".equals(kind)))throw accountValidation("includeInAvailableCash","负债账户不能计入可用现金。");}
    private void validateAccountOpeningOn(LocalDate openingOn) throws AccountException {
        if (openingOn == null) throw accountValidation("openingOn", "开户日不能为空。");
        LocalDate start = LocalDate.parse(activeContext.snapshot.getLedgerStartOn());
        if (openingOn.isBefore(start)) throw accountValidation("openingOn", "开户日不能早于账本起始日。");
    }
    private static String kindSide(String kind){return Arrays.asList("CREDIT","LOAN","OTHER_LIABILITY").contains(kind)?"LIABILITY":"ASSET";}
    private static void requireAccountRevision(AccountRecord a,long expected)throws AccountException{if(a.revision!=expected)throw new AccountException(409,"REVISION_CONFLICT","账户已被更新。",Collections.emptyMap(),Collections.singletonMap("currentRevision",a.revision));}
    private static AccountException accountValidation(String field,String message){return new AccountException(400,"VALIDATION_FAILED","请求参数无效。",Collections.singletonMap(field,message),Collections.emptyMap());}
    private static AccountException accountNotFound(){return new AccountException(404,"NOT_FOUND","账户不存在。");}
    private static AccountException accountReference(String reason){return new AccountException(409,"REFERENCE_CONFLICT","账户状态冲突。",Collections.emptyMap(),Collections.singletonMap("reason",reason));}
    private static AccountException accountUnavailable(){return new AccountException(503,"SERVICE_UNAVAILABLE","本地数据暂时不可用。");}
    private static AccountException accountInternal(){return new AccountException(500,"INTERNAL_ERROR","本地服务发生内部错误。");}

    private LedgerCatalogRepository recordRepository() throws RecordException { requireRecordContext(); return new LedgerCatalogRepository(activeContext.ledgerFile); }
    private void requireRecordContext() throws RecordException { if(activeContext==null)throw new RecordException(423,"RECOVERY_REQUIRED","数据恢复完成前不能操作记录。"); if(!setupComplete())throw new RecordException(409,"LEDGER_SETUP_REQUIRED","请先完成账本初始化。"); }
    private void refreshRecordContext(long revision){if(revision>=0&&activeContext!=null)activeContext=new ActiveProfileContext(new BootstrapSnapshot(activeContext.snapshot.getProfileId(),activeContext.snapshot.getSchemaVersion(),revision,activeContext.snapshot.getSetupState(),activeContext.snapshot.getLedgerStartOn()),activeContext.ledgerFile);}
    private static boolean isBasicRecord(String type){return "INCOME".equals(type)||"FIXED_COST".equals(type)||"VARIABLE_COST".equals(type);}
    private static void validateRecordId(String id)throws RecordException{if(!isLowercaseUuid(id))throw recordValidation("id","必须是小写 UUID。");}
    private static void validateRecordPatch(RecordPatch p,boolean create)throws RecordException{if(p==null)throw recordValidation("body","请求体必须是对象。");if(create)validateRecordId(p.getId());if(p.getType()==null)throw recordValidation("recordType","记录类型无效。");if(p.getAmountMinor()<=0)throw recordValidation("amount","金额必须大于 0。");if(p.getOccurredOn()==null)throw recordValidation("occurredOn","日期不能为空。");if(p.getSettlementOn()==null)throw recordValidation("settlement.settlementOn","日期不能为空。");if(p.getNote()==null||p.getNote().codePointCount(0,p.getNote().length())>4000)throw recordValidation("note","备注长度不能超过 4000 个字符。");validateRecordId(p.getCategoryId());validateRecordId(p.getAccountId());}
    private void validateRecordReferences(Connection c,RecordPatch p)throws RecordException,SQLException{LocalDate start=LocalDate.parse(activeContext.snapshot.getLedgerStartOn());if(p.getOccurredOn().isBefore(start))throw recordValidation("occurredOn","发生日不能早于账本起始日。");if(p.getSettlementOn().isBefore(start))throw recordValidation("settlement.settlementOn","结算日不能早于账本起始日。");LedgerCatalogRepository repo=recordRepository();CategoryRecord category=repo.findCategory(c,p.getCategoryId());if(category==null||category.archivedAt!=null)throw recordReference("CATEGORY_NOT_ACTIVE");if(category.system&&!repo.recordTypes(c,p.getCategoryId()).contains(p.getType().name()))throw recordReference("CATEGORY_RECORD_TYPE_MISMATCH");AccountRecord account=repo.findAccount(c,p.getAccountId(),p.getSettlementOn());if(account==null||account.archivedAt!=null)throw recordReference("ACCOUNT_NOT_ACTIVE");if(p.getSettlementOn().isBefore(account.openingOn))throw new RecordException(400,"ACCOUNT_NOT_OPEN_ON_SETTLEMENT_DATE","结算日早于账户开户日。",Collections.singletonMap("settlement.settlementOn","结算日不能早于账户开户日。"),Collections.emptyMap());}
    private RecordApiResult replayRecord(Connection c,RecordMutation m,Instant now)throws RecordException,PersistenceException{LedgerOperation op=settings.findOperation(c,m.getIdempotencyKey(),now);if(op==null)return null;if(!op.getHttpMethod().equals(m.getHttpMethod())||!op.getCanonicalPath().equals(m.getCanonicalPath())||!op.getRequestHash().equals(m.getRequestHash()))throw new RecordException(409,"IDEMPOTENCY_CONFLICT","幂等键已用于不同请求。");try{JsonNode body=objectMapper.readTree(op.getResponseJson());long revision=body.at("/data/record/revision").asLong(-1);if(revision<0)throw new IllegalArgumentException();return new RecordApiResult(op.getResponseStatus(),op.getResponseJson(),"\""+revision+"\"",null);}catch(Exception ex){throw recordInternal();}}
    private void insertRecordOperation(Connection c,RecordMutation m,RecordApiResult r,String t,Instant now)throws PersistenceException{settings.insertOperation(c,new LedgerOperation(m.getIdempotencyKey(),m.getHttpMethod(),m.getCanonicalPath(),m.getRequestHash(),r.getStatus(),r.getResponseJson(),activeContext.snapshot.getProfileId(),t,now.plus(OPERATION_RETENTION).toString()));}
    private RecordApiResult recordSuccess(int status,Map<String,Object> data,long revision,String etag,String location)throws RecordException{Map<String,Object> body=new LinkedHashMap<>();body.put("data",data);Map<String,Object> meta=new LinkedHashMap<>();meta.put("dataRevision",revision);body.put("meta",meta);try{return new RecordApiResult(status,objectMapper.writeValueAsString(body),etag,location);}catch(JsonProcessingException ex){throw recordInternal();}}
    private static Map<String,Object> recordMap(RecordRecord r){Map<String,Object> d=new LinkedHashMap<>();d.put("id",r.id);d.put("revision",r.revision);d.put("status",r.deletedAt==null?"ACTIVE":"TRASHED");d.put("occurredOn",r.occurredOn);d.put("recordType",r.recordType);d.put("amount",formatMoney(r.amountMinor));d.put("currency","CNY");Map<String,Object> category=new LinkedHashMap<>();category.put("id",r.categoryId);category.put("name",r.categoryName);category.put("status",r.categoryArchived==null?"ACTIVE":"ARCHIVED");d.put("category",category);Map<String,Object> account=new LinkedHashMap<>();account.put("id",r.accountId);account.put("name",r.accountName);account.put("status",r.accountArchived==null?"ACTIVE":"ARCHIVED");Map<String,Object> settlement=new LinkedHashMap<>();settlement.put("mode",r.settlementMode);settlement.put("account",account);settlement.put("settlementOn",r.settlementOn);d.put("settlement",settlement);d.put("note",r.note);d.put("createdAt",r.createdAt);d.put("updatedAt",r.updatedAt);d.put("deletedAt",r.deletedAt);return d;}
    private static void validateRecordQuery(RecordQuery query) throws RecordException {
        if (query == null) throw recordValidation("query", "查询参数无效。");
        if (!"ACTIVE".equals(query.getStatus()) && !"TRASHED".equals(query.getStatus())) throw recordValidation("status", "必须是 ACTIVE 或 TRASHED。");
        if (query.getLimit() < 1 || query.getLimit() > 200) throw recordValidation("limit", "必须在 1 到 200 之间。");
        if (query.getCursor() != null && query.getCursor().trim().isEmpty()) throw recordValidation("cursor", "游标无效。");
        if (query.getOccurredFrom() != null && query.getOccurredToExclusive() != null && !query.getOccurredToExclusive().isAfter(query.getOccurredFrom())) throw recordValidation("occurredToExclusive", "必须晚于 occurredFrom。");
        if (query.getRecordTypes().size() > 3) throw recordValidation("recordType", "最多选择三种记录类型。");
        validateDistinctRecordValues(query.getRecordTypes(), "recordType");
        validateDistinctRecordValues(query.getCategoryIds(), "categoryId");
        validateDistinctRecordValues(query.getAccountIds(), "accountId");
        if (query.getCategoryIds().size() > 200) throw recordValidation("categoryId", "最多选择 200 个分类。");
        if (query.getAccountIds().size() > 200) throw recordValidation("accountId", "最多选择 200 个账户。");
        for (String type : query.getRecordTypes()) if (!Arrays.asList("INCOME", "FIXED_COST", "VARIABLE_COST").contains(type)) throw recordValidation("recordType", "记录类型无效。");
        for (String id : query.getCategoryIds()) validateRecordId(id);
        for (String id : query.getAccountIds()) validateRecordId(id);
        if (query.getQuery() != null && (query.getQuery().trim().isEmpty() || query.getQuery().codePointCount(0, query.getQuery().length()) > 200)) throw recordValidation("query", "query 长度必须为 1 到 200 个字符。");
        if (query.getAmountMinMinor() != null && query.getAmountMinMinor() < 0) throw recordValidation("amountMin", "金额不能为负数。");
        if (query.getAmountMaxMinor() != null && query.getAmountMaxMinor() < 0) throw recordValidation("amountMax", "金额不能为负数。");
        if (query.getAmountMinMinor() != null && query.getAmountMaxMinor() != null && query.getAmountMinMinor() > query.getAmountMaxMinor()) throw recordValidation("amountMin", "amountMin 不能大于 amountMax。");
    }
    private static void validateDistinctRecordValues(List<String> values, String field) throws RecordException {
        if (new HashSet<>(values).size() != values.size()) throw recordValidation(field, "查询参数不能重复。");
    }
    private static int recordCursorStart(List<RecordRecord> rows,RecordQuery query,long revision)throws RecordException{if(query.getCursor()==null)return 0;try{String[] f=new String(Base64.getUrlDecoder().decode(query.getCursor()),StandardCharsets.UTF_8).split("\\n",-1);String key=Base64.getUrlEncoder().withoutPadding().encodeToString(query.canonical().getBytes(StandardCharsets.UTF_8));if(f.length!=4||!"v2".equals(f[0])||!key.equals(f[1])||Long.parseLong(f[2])!=revision)throw new IllegalArgumentException();for(int i=0;i<rows.size();i++)if(rows.get(i).id.equals(f[3]))return i+1;throw new IllegalArgumentException();}catch(Exception ex){throw recordValidation("cursor","游标无效或已过期。");}}
    private static String recordCursor(RecordRecord r,RecordQuery query,long revision){String key=Base64.getUrlEncoder().withoutPadding().encodeToString(query.canonical().getBytes(StandardCharsets.UTF_8));return Base64.getUrlEncoder().withoutPadding().encodeToString(("v2\n"+key+"\n"+revision+"\n"+r.id).getBytes(StandardCharsets.UTF_8));}
    private static void validateRecordMutation(RecordMutation m,String method,String path)throws RecordException{if(m==null||m.getIdempotencyKey()==null||m.getIdempotencyKey().trim().isEmpty()||!method.equals(m.getHttpMethod())||!path.equals(m.getCanonicalPath())||m.getRequestHash()==null||!m.getRequestHash().matches("[0-9a-f]{64}"))throw recordValidation("request","请求幂等信息无效。");}
    private static RecordException recordValidation(String field,String message){return new RecordException(400,"VALIDATION_FAILED","请求参数无效。",Collections.singletonMap(field,message),Collections.emptyMap());}
    private static RecordException recordNotFound(){return new RecordException(404,"NOT_FOUND","记录不存在。");}
    private static RecordException recordReference(String reason){return new RecordException(409,"REFERENCE_CONFLICT","记录状态或引用冲突。",Collections.emptyMap(),Collections.singletonMap("reason",reason));}
    private static RecordException recordRevision(long current){return new RecordException(409,"REVISION_CONFLICT","记录已被更新。",Collections.emptyMap(),Collections.singletonMap("currentRevision",current));}
    private static RecordException recordUnavailable(){return new RecordException(503,"SERVICE_UNAVAILABLE","本地数据暂时不可用。");}
    private static RecordException recordInternal(){return new RecordException(500,"INTERNAL_ERROR","本地服务发生内部错误。");}

    private static void validateSettingsMutation(SettingsMutation mutation) throws SettingsException {
        if (mutation == null || !"PATCH".equals(mutation.getHttpMethod())
                || !"/api/v1/settings".equals(mutation.getCanonicalPath())
                || mutation.getRequestHash() == null || !mutation.getRequestHash().matches("[0-9a-f]{64}")
                || !isLowercaseUuid(mutation.getIdempotencyKey())) {
            throw settingsValidation("request", "请求幂等信息无效。");
        }
    }

    private static void validateSettingsPatch(SettingsPatch patch) throws SettingsException {
        if (patch == null || patch.isEmpty()) {
            throw settingsValidation("body", "至少需要一个可写设置字段。");
        }
        if (patch.getAutoBackupIntervalDays() != null
                && (patch.getAutoBackupIntervalDays() < 1 || patch.getAutoBackupIntervalDays() > 365)) {
            throw settingsValidation("autoBackupIntervalDays", "必须是 1 到 365 的整数。");
        }
        if (patch.getAutoBackupRetentionCount() != null
                && (patch.getAutoBackupRetentionCount() < 1 || patch.getAutoBackupRetentionCount() > 100)) {
            throw settingsValidation("autoBackupRetentionCount", "必须是 1 到 100 的整数。");
        }
        if (patch.getLastSettingsSection() != null
                && !Arrays.asList("GENERAL", "PROFILES", "CATEGORIES", "ACCOUNTS")
                        .contains(patch.getLastSettingsSection())) {
            throw settingsValidation("lastSettingsSection", "不是受支持的设置页。");
        }
        if (patch.getSafetyBufferMinor() != null && patch.getSafetyBufferMinor() < 0L) {
            throw settingsValidation("safetyBuffer.amount", "必须是非负金额。");
        }
        if (patch.getThemeName() != null
                && !Arrays.asList("WARM_COPPER", "GRAPHITE", "DEEP_SEA_BLUE").contains(patch.getThemeName())) {
            throw settingsValidation("themeName", "必须是受支持的内置主题。");
        }
    }

    private static boolean isLowercaseUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean sameRequest(CatalogOperation operation, ProfileMutation mutation) {
        return operation.getHttpMethod().equals(mutation.getHttpMethod())
                && operation.getCanonicalPath().equals(mutation.getCanonicalPath())
                && operation.getRequestHash().equals(mutation.getRequestHash());
    }

    private static ProfileException referenceConflict(String reason) {
        return new ProfileException(409, "REFERENCE_CONFLICT", "用户空间状态冲突。",
                Collections.emptyMap(), Collections.singletonMap("reason", reason));
    }

    private static ProfileException targetUnavailable() {
        return new ProfileException(409, "REFERENCE_CONFLICT", "目标用户空间不可用。",
                Collections.emptyMap(), Collections.singletonMap("reason", "TARGET_PROFILE_UNAVAILABLE"));
    }

    private static ProfileException idempotencyConflict() {
        return new ProfileException(409, "IDEMPOTENCY_CONFLICT", "幂等键已用于不同请求。");
    }

    private static ProfileException validation(String field, String message) {
        return new ProfileException(400, "VALIDATION_FAILED", "请求参数无效。",
                Collections.singletonMap(field, message), Collections.emptyMap());
    }

    private static SettingsException settingsValidation(String field, String message) {
        return new SettingsException(400, "VALIDATION_FAILED", "请求参数无效。",
                Collections.singletonMap(field, message), Collections.emptyMap());
    }

    private static SettingsException settingsRevisionConflict(long currentRevision) {
        return new SettingsException(409, "REVISION_CONFLICT", "设置已被更新。", Collections.emptyMap(),
                Collections.singletonMap("currentRevision", currentRevision));
    }

    private static SettingsException settingsIdempotencyConflict() {
        return new SettingsException(409, "IDEMPOTENCY_CONFLICT", "幂等键已用于不同请求。");
    }

    private static SettingsException settingsUnavailable() {
        return new SettingsException(503, "SERVICE_UNAVAILABLE", "本地数据暂时不可用。");
    }

    private static SettingsException settingsInternal() {
        return new SettingsException(500, "INTERNAL_ERROR", "本地服务发生内部错误。");
    }

    private static ProfileException unavailable(Exception cause) {
        return new ProfileException(503, "SERVICE_UNAVAILABLE", "本地数据暂时不可用。");
    }

    private static ProfileException internal(Exception cause) {
        return new ProfileException(500, "INTERNAL_ERROR", "本地服务发生内部错误。");
    }

    private CatalogOperation operationFrom(ProfileMutation mutation, ProfileApiResult result, Instant now) {
        return new CatalogOperation(mutation.getIdempotencyKey(), mutation.getHttpMethod(), mutation.getCanonicalPath(),
                mutation.getRequestHash(), result.getStatus(), result.getResponseJson(), result.getEtag(), result.getLocation(),
                now.toString(), now.plus(OPERATION_RETENTION).toString());
    }

    private ProfileApiResult success(int status, Map<String, Object> data, Map<String, Object> meta,
            String etag, String location) throws ProfileException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("meta", meta);
        try {
            return new ProfileApiResult(status, objectMapper.writeValueAsString(body), etag, location);
        } catch (JsonProcessingException ex) {
            throw internal(ex);
        }
    }

    private static Map<String, Object> createOrActivateData(ProfileView profile, String previousActiveProfileId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profile", profile.toMap());
        data.put("previousActiveProfileId", previousActiveProfileId);
        return data;
    }

    private void requireMetricContext() throws MetricException {
        if (activeContext == null) throw new MetricException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能读取指标。");
        if (!setupComplete()) throw new MetricException(409, "LEDGER_SETUP_REQUIRED", "请先完成账本初始化。");
    }

    private void requireDashboardContext() throws DashboardException {
        if (activeContext == null) throw new DashboardException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能读取总览。");
        if (!setupComplete()) throw new DashboardException(409, "LEDGER_SETUP_REQUIRED", "请先完成账本初始化。");
    }

    private void validateMetricId(String id) throws MetricException {
        if (id == null || !id.matches("custom-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            throw metricValidation("id", "必须是 custom- 加小写 UUID v4。");
        }
    }

    private void validateMetricMutation(MetricMutation mutation, String method, String path) throws MetricException {
        if (mutation == null || mutation.getIdempotencyKey() == null || mutation.getIdempotencyKey().trim().isEmpty()) {
            throw metricValidation("Idempotency-Key", "缺少幂等键。");
        }
        if (!method.equals(mutation.getHttpMethod()) || !path.equals(mutation.getCanonicalPath())
                || mutation.getRequestHash() == null || mutation.getRequestHash().trim().isEmpty()) {
            throw metricValidation("request", "请求幂等信息无效。");
        }
    }

    private void validateMetricDraft(MetricDraft draft) throws MetricException {
        if (draft == null) throw metricValidation("body", "请求体必须是对象。");
        if (draft.getName() == null || draft.getName().trim().isEmpty() || !draft.getName().equals(draft.getName().trim())) {
            throw metricValidation("name", "名称不能为空且不能包含首尾空格。");
        }
        if (draft.getName().length() > 80) throw metricValidation("name", "名称不能超过 80 个字符。");
        if (draft.getDescription() != null && draft.getDescription().trim().isEmpty() && !draft.getDescription().isEmpty()) {
            throw metricValidation("description", "描述不能只有空白字符。");
        }
        if (draft.getDisplayFormat() == null || !("CURRENCY".equals(draft.getDisplayFormat())
                || "PERCENT".equals(draft.getDisplayFormat()) || "NUMBER".equals(draft.getDisplayFormat())
                || "INTEGER".equals(draft.getDisplayFormat()))) {
            throw metricValidation("displayFormat", "显示格式无效。");
        }
        if (draft.getPrecision() < 0 || draft.getPrecision() > 8
                || ("CURRENCY".equals(draft.getDisplayFormat()) && draft.getPrecision() != 2)
                || ("INTEGER".equals(draft.getDisplayFormat()) && draft.getPrecision() != 0)) {
            throw metricValidation("precision", "精度与显示格式不匹配。");
        }
        if (draft.getFormula() == null) throw metricValidation("formula.ast", "公式不能为空。");
    }

    private FormulaValidationResult validateFormula(Connection connection, String owner, FormulaNode root)
            throws SQLException {
        Set<String> known = new HashSet<>();
        for (MetricDefinitionRecord metric : new LedgerMetricsRepository(activeContext.ledgerFile)
                .listMetricDefinitions(connection, false)) known.add(metric.id);
        FormulaValidationResult base = new FormulaValidator().validate(root, owner, known,
                new LedgerMetricsRepository(activeContext.ledgerFile).activeMetricDependencyGraph(connection));
        List<com.ledgerx.application.metrics.FormulaError> errors = new ArrayList<>(base.getErrors());
        LedgerCatalogRepository catalogRepository = new LedgerCatalogRepository(activeContext.ledgerFile);
        for (FormulaReference reference : base.getDependencies()) {
            if (reference.getKind() == FormulaReference.Kind.CATEGORY_INCOME
                    || reference.getKind() == FormulaReference.Kind.CATEGORY_EXPENSE) {
                CategoryRecord category = catalogRepository.findCategory(connection, reference.getKey());
                if (category == null || category.archivedAt != null) {
                    errors.add(new com.ledgerx.application.metrics.FormulaError("REFERENCE_CONFLICT",
                            "formula.ast.ref", "引用的分类不存在或已归档。"));
                }
            } else if (reference.getKind() == FormulaReference.Kind.ACCOUNT_BALANCE) {
                AccountRecord account = catalogRepository.findAccount(connection, reference.getKey(), businessToday());
                if (account == null || account.archivedAt != null) {
                    errors.add(new com.ledgerx.application.metrics.FormulaError("REFERENCE_CONFLICT",
                            "formula.ast.ref", "引用的账户不存在或已归档。"));
                }
            }
        }
        return new FormulaValidationResult(errors, base.getDependencies());
    }

    private void requireFormulaValid(FormulaValidationResult result) throws MetricException {
        if (result.isValid()) return;
        Map<String, String> fields = new LinkedHashMap<>();
        for (com.ledgerx.application.metrics.FormulaError error : result.getErrors()) {
            fields.putIfAbsent(error.getPath(), error.getMessage());
        }
        String code = result.getErrors().stream().anyMatch(error -> "FORMULA_CYCLE".equals(error.getCode()))
                ? "FORMULA_CYCLE" : result.getErrors().stream().anyMatch(error -> "REFERENCE_CONFLICT".equals(error.getCode()))
                        ? "REFERENCE_CONFLICT" : "FORMULA_INVALID";
        int status = ("FORMULA_CYCLE".equals(code) || "REFERENCE_CONFLICT".equals(code)) ? 409 : 400;
        throw new MetricException(status, code, "公式未通过校验。", fields, Collections.emptyMap());
    }

    private static List<LedgerMetricsRepository.DependencyRecord> dependencyRecords(Set<FormulaReference> dependencies) {
        List<LedgerMetricsRepository.DependencyRecord> result = new ArrayList<>();
        for (FormulaReference reference : dependencies) {
            String kind = reference.getKind().name();
            String metric = reference.getKind() == FormulaReference.Kind.METRIC ? reference.getKey() : null;
            String category = (reference.getKind() == FormulaReference.Kind.CATEGORY_INCOME
                    || reference.getKind() == FormulaReference.Kind.CATEGORY_EXPENSE) ? reference.getKey() : null;
            String account = reference.getKind() == FormulaReference.Kind.ACCOUNT_BALANCE ? reference.getKey() : null;
            result.add(new LedgerMetricsRepository.DependencyRecord(kind, reference.getKey(), metric, category, account));
        }
        return result;
    }

    private MetricApiResult replayMetric(Connection connection, MetricMutation mutation, Instant now)
            throws PersistenceException, MetricException {
        LedgerOperation operation = settings.findOperation(connection, mutation.getIdempotencyKey(), now);
        if (operation == null) return null;
        if (!operation.getHttpMethod().equals(mutation.getHttpMethod())
                || !operation.getCanonicalPath().equals(mutation.getCanonicalPath())
                || !operation.getRequestHash().equals(mutation.getRequestHash())) {
            throw new MetricException(409, "IDEMPOTENCY_CONFLICT", "幂等键已用于不同请求。");
        }
        try {
            JsonNode body = objectMapper.readTree(operation.getResponseJson());
            long revision = body.at("/data/metric/revision").asLong(-1L);
            return new MetricApiResult(operation.getResponseStatus(), operation.getResponseJson(),
                    revision < 0 ? null : quoteEtag(revision), null);
        } catch (IOException ex) { throw metricInternal(); }
    }

    private LedgerOperation metricOperation(MetricMutation mutation, MetricApiResult result, String timestamp, Instant now) {
        return new LedgerOperation(mutation.getIdempotencyKey(), mutation.getHttpMethod(), mutation.getCanonicalPath(),
                mutation.getRequestHash(), result.getStatus(), result.getResponseJson(), activeContext.snapshot.getProfileId(),
                timestamp, now.plus(OPERATION_RETENTION).toString());
    }

    private void refreshMetricContext(long revision) {
        if (revision >= 0 && activeContext != null) {
            activeContext = new ActiveProfileContext(new BootstrapSnapshot(activeContext.snapshot.getProfileId(),
                    activeContext.snapshot.getSchemaVersion(), revision, activeContext.snapshot.getSetupState(),
                    activeContext.snapshot.getLedgerStartOn()), activeContext.ledgerFile);
        }
    }

    private static MetricException metricRevision(long currentRevision) {
        return new MetricException(409, "REVISION_CONFLICT", "指标版本已变化。", Collections.emptyMap(),
                Collections.singletonMap("currentRevision", currentRevision));
    }

    private long repositoryDataRevision(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT data_revision FROM ledger_meta WHERE id=1");
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    private static int metricCursorStart(List<MetricDefinitionRecord> metrics, MetricListQuery query,
            long dataRevision) throws MetricException {
        if (query.getCursor() == null) return 0;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(query.getCursor()), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\n", -1);
            if (parts.length != 5 || !"v1".equals(parts[0]) || !query.getStatus().equals(parts[1])
                    || !metricCursorEnabled(query).equals(parts[2])
                    || dataRevision != Long.parseLong(parts[3])) throw new IllegalArgumentException();
            for (int index = 0; index < metrics.size(); index++) {
                if (metrics.get(index).id.equals(parts[4])) return index + 1;
            }
        } catch (IllegalArgumentException ex) {
            // Normalized below to keep the opaque cursor implementation private.
        }
        throw metricValidation("cursor", "游标无效或数据已更新。");
    }

    private static String metricCursor(MetricDefinitionRecord metric, MetricListQuery query, long dataRevision) {
        String value = "v1\n" + query.getStatus() + "\n" + metricCursorEnabled(query) + "\n"
                + dataRevision + "\n" + metric.id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String metricCursorEnabled(MetricListQuery query) {
        return query.getDashboardEnabled() == null ? "" : query.getDashboardEnabled().toString();
    }

    private static Map<String, VisibilityRecord> visibilityById(List<VisibilityRecord> values) {
        Map<String, VisibilityRecord> result = new HashMap<>();
        for (VisibilityRecord value : values) result.put(value.metricId, value);
        return result;
    }

    private static Map<String, Object> metricView(MetricDefinitionRecord metric, VisibilityRecord visibility) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", metric.id); result.put("name", metric.name); result.put("description", metric.description);
        result.put("displayFormat", metric.displayFormat); result.put("precision", metric.precision);
        result.put("periodBehavior", metric.periodBehavior); result.put("isSystem", metric.system);
        result.put("status", metric.archivedAt == null ? "ACTIVE" : "ARCHIVED");
        Map<String, Object> visible = new LinkedHashMap<>();
        if (visibility != null) {
            visible.put("hidden", visibility.hidden); visible.put("dashboardEnabled", visibility.dashboardEnabled);
            visible.put("revision", visibility.revision);
        }
        result.put("visibility", visible); result.put("currentFormulaVersion", null);
        result.put("revision", metric.revision); result.put("createdAt", metric.createdAt); result.put("updatedAt", metric.updatedAt);
        return result;
    }

    private Map<String, Object> metricSummaryView(MetricDefinitionRecord metric, VisibilityRecord visibility,
            LedgerMetricsRepository repository, Connection connection) throws SQLException {
        Map<String, Object> result = metricView(metric, visibility);
        FormulaVersionRecord version = repository.findFormulaVersion(connection, metric.currentFormulaVersionId);
        if (version != null) result.put("currentFormulaVersion", formulaVersionSummary(version));
        return result;
    }

    private Map<String, Object> metricDetailView(MetricDefinitionRecord metric, VisibilityRecord visibility,
            LedgerMetricsRepository repository, Connection connection) throws SQLException, MetricException {
        Map<String, Object> result = metricSummaryView(metric, visibility, repository, connection);
        FormulaVersionRecord version = repository.findFormulaVersion(connection, metric.currentFormulaVersionId);
        if (version == null) {
            result.put("formula", null);
            return result;
        }
        try {
            Map<String, Object> formula = formulaVersionSummary(version);
            FormulaNode root = FormulaStorageMapper.fromAst(objectMapper.readTree(version.astJson));
            formula.put("ast", FormulaHttpMapper.toAst(root));
            formula.put("tokens", CanonicalFormulaTokens.from(root));
            formula.put("dependencies", dependencyViews(dependencyReferences(repository.listDependencies(connection, version.id)),
                    repository, connection));
            result.put("formula", formula);
            return result;
        } catch (IOException | RuntimeException ex) {
            throw metricInternal();
        }
    }

    private static Map<String, Object> formulaVersionSummary(FormulaVersionRecord version) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("formulaId", version.formulaId); result.put("versionId", version.id);
        result.put("version", version.version); result.put("createdAt", version.createdAt);
        return result;
    }

    private static Set<FormulaReference> dependencyReferences(List<LedgerMetricsRepository.DependencyRecord> dependencies) {
        Set<FormulaReference> references = new LinkedHashSet<>();
        for (LedgerMetricsRepository.DependencyRecord dependency : dependencies) {
            references.add(new FormulaReference(FormulaReference.Kind.valueOf(dependency.kind), dependency.key));
        }
        return references;
    }

    /** Builds the one immutable evaluation environment shared by preview and dashboard calculations. */
    private static FormulaEvaluationContext formulaContext(Map<String, BigDecimal> metricValues,
            MetricFactsRecord facts) {
        Map<String, BigDecimal> time = new HashMap<>();
        long periodDays = java.time.temporal.ChronoUnit.DAYS.between(facts.period.getStart(),
                facts.period.getEndExclusive());
        long elapsedDays = facts.period.getAsOf() == null ? 0L
                : java.time.temporal.ChronoUnit.DAYS.between(facts.period.getStart(), facts.period.getAsOf()) + 1L;
        long completeMonths = java.time.temporal.ChronoUnit.MONTHS.between(facts.period.getStart(),
                facts.period.getAsOf() == null ? facts.period.getStart() : facts.period.getAsOf().plusDays(1));
        time.put("period-days", BigDecimal.valueOf(periodDays));
        time.put("elapsed-days", BigDecimal.valueOf(Math.max(0L, elapsedDays)));
        time.put("complete-months", BigDecimal.valueOf(Math.max(0L, completeMonths)));
        return new FormulaEvaluationContext(metricValues, facts.categoryIncome, facts.categoryExpense,
                facts.accountBalances, time);
    }

    private List<Map<String, Object>> previewComponents(Set<FormulaReference> dependencies,
            Map<String, BigDecimal> metricValues, MetricFactsRecord facts, LedgerMetricsRepository repository,
            Connection connection) throws SQLException {
        Map<String, VisibilityRecord> visibility = visibilityById(repository.listVisibility(connection));
        List<Map<String, Object>> components = new ArrayList<>();
        for (FormulaReference reference : dependencies) {
            Map<String, Object> component = new LinkedHashMap<>();
            component.put("kind", reference.getKind().name()); component.put("key", reference.getKey());
            component.put("label", dependencyLabel(reference, repository, connection));
            BigDecimal value = null;
            boolean hidden = false;
            switch (reference.getKind()) {
                case METRIC:
                    value = metricValues.get(reference.getKey());
                    VisibilityRecord visible = visibility.get(reference.getKey());
                    hidden = visible != null && visible.hidden;
                    break;
                case CATEGORY_INCOME: value = facts.categoryIncome.get(reference.getKey()); break;
                case CATEGORY_EXPENSE: value = facts.categoryExpense.get(reference.getKey()); break;
                case ACCOUNT_BALANCE: value = facts.accountBalances.get(reference.getKey()); break;
                case TIME: value = formulaContext(Collections.emptyMap(), facts).resolve(reference); break;
                default: break;
            }
            component.put("value", hidden || value == null ? null : value.toPlainString());
            component.put("dataStatus", value == null ? MetricDataStatus.DEPENDENCY_UNAVAILABLE.name()
                    : MetricDataStatus.READY.name());
            components.add(component);
        }
        return components;
    }

    private String dependencyLabel(FormulaReference reference, LedgerMetricsRepository repository,
            Connection connection) throws SQLException {
        if (reference.getKind() == FormulaReference.Kind.METRIC) {
            MetricDefinitionRecord metric = repository.findMetric(connection, reference.getKey());
            return metric == null ? reference.getKey() : metric.name;
        }
        LedgerCatalogRepository catalogRepository = new LedgerCatalogRepository(activeContext.ledgerFile);
        if (reference.getKind() == FormulaReference.Kind.CATEGORY_INCOME
                || reference.getKind() == FormulaReference.Kind.CATEGORY_EXPENSE) {
            CategoryRecord category = catalogRepository.findCategory(connection, reference.getKey());
            return category == null ? reference.getKey() : category.name;
        }
        if (reference.getKind() == FormulaReference.Kind.ACCOUNT_BALANCE) {
            AccountRecord account = catalogRepository.findAccount(connection, reference.getKey(), businessToday());
            return account == null ? reference.getKey() : account.name;
        }
        return timeReferenceLabel(reference.getKey());
    }

    private static String timeReferenceLabel(String key) {
        if ("period-days".equals(key)) return "期间天数";
        if ("elapsed-days".equals(key)) return "已过天数";
        if ("complete-months".equals(key)) return "完整月份数";
        return key;
    }

    private FormulaEvaluationResult calculateMetricValue(String metricId, LedgerMetricsRepository repository,
            Connection connection, MetricFactsRecord facts,
            SystemMetricCalculator calculator, FormulaEvaluator evaluator,
            Map<String, FormulaEvaluationResult> memo, Set<String> visiting) {
        FormulaEvaluationResult cached = memo.get(metricId);
        if (cached != null) return cached;
        if (!visiting.add(metricId)) return FormulaEvaluationResult.unavailable();
        try {
            MetricDefinitionRecord metric = repository.findMetric(connection, metricId);
            if (metric == null || metric.archivedAt != null) return FormulaEvaluationResult.unavailable();
            if (metric.system) {
                FormulaEvaluationResult result = calculator.calculate(metricId, facts.facts);
                memo.put(metricId, result);
                return result;
            }
            FormulaVersionRecord version = repository.findFormulaVersion(connection, metric.currentFormulaVersionId);
            if (version == null) return FormulaEvaluationResult.unavailable();
            JsonNode document = objectMapper.readTree(version.astJson);
            FormulaNode root = FormulaStorageMapper.fromAst(document);
            Map<String, BigDecimal> metricValues = new HashMap<>();
            for (FormulaReference reference : storedReferences(root)) {
                if (reference.getKind() != FormulaReference.Kind.METRIC) continue;
                FormulaEvaluationResult dependency = calculateMetricValue(reference.getKey(), repository, connection,
                        facts, calculator, evaluator, memo, visiting);
                if (!dependency.isReady()) return dependency;
                metricValues.put(reference.getKey(), dependency.getValue());
            }
            FormulaEvaluationResult result = evaluator.evaluate(root, formulaContext(metricValues, facts));
            memo.put(metricId, result);
            return result;
        } catch (Exception ex) {
            return FormulaEvaluationResult.unavailable();
        } finally {
            visiting.remove(metricId);
        }
    }

    private static Set<FormulaReference> storedReferences(FormulaNode node) {
        Set<FormulaReference> result = new LinkedHashSet<>();
        collectStoredReferences(node, result);
        return result;
    }

    private static void collectStoredReferences(FormulaNode node, Set<FormulaReference> result) {
        if (node == null) return;
        if (node.getKind() == FormulaNode.Kind.REF) result.add(node.getReference());
        for (FormulaNode child : node.getChildren()) collectStoredReferences(child, result);
    }

    private static Map<String, Object> metricValue(FormulaEvaluationResult result, MetricDefinitionRecord metric) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("value", result.getValue() == null ? null : result.getValue().toPlainString());
        value.put("displayFormat", metric.displayFormat);
        value.put("precision", metric.precision);
        value.put("dataStatus", result.getStatus().name());
        return value;
    }

    private static Map<String, Object> changeValue(FormulaEvaluationResult current, FormulaEvaluationResult previous) {
        Map<String, Object> change = new LinkedHashMap<>();
        if (!current.isReady() || !previous.isReady()) {
            change.put("absolute", null); change.put("percent", null);
            change.put("dataStatus", current.isReady() ? previous.getStatus().name() : current.getStatus().name());
            return change;
        }
        BigDecimal absolute = current.getValue().subtract(previous.getValue());
        change.put("absolute", absolute.toPlainString());
        change.put("percent", previous.getValue().signum() == 0 ? null
                : absolute.multiply(BigDecimal.valueOf(100)).divide(previous.getValue(), java.math.MathContext.DECIMAL128).toPlainString());
        change.put("dataStatus", MetricDataStatus.READY.name());
        return change;
    }

    private static ResolvedPeriod comparisonPeriod(ResolvedPeriod period) {
        LocalDate end = period.getComparisonEndExclusive();
        LocalDate asOf = end.minusDays(1);
        DashboardPeriod request = new DashboardPeriod(period.getRequested().getGranularity(), period.getComparisonStart());
        return new ResolvedPeriod(request, period.getComparisonStart(), end, asOf,
                period.getComparisonStart().minusDays(Math.max(1,
                        java.time.temporal.ChronoUnit.DAYS.between(period.getComparisonStart(), end))),
                period.getComparisonStart(), period.getComparisonStart(), end, PeriodStatus.HISTORICAL);
    }

    private static Map<String, Object> layoutView(LayoutRecord layout) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (layout == null) return result;
        result.put("viewKey", layout.viewKey); result.put("breakpoint", layout.breakpoint); result.put("revision", layout.revision);
        List<Map<String, Object>> items = new ArrayList<>();
        for (LayoutItemRecord item : layout.items) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("widgetId", item.widgetId); value.put("x", item.x); value.put("y", item.y);
            value.put("w", item.w); value.put("h", item.h); value.put("minW", item.minW); value.put("minH", item.minH);
            value.put("maxW", item.maxW); value.put("maxH", item.maxH); value.put("persisted", true); items.add(value);
        }
        result.put("items", items); return result;
    }

    private static Map<String, Object> dashboardLayoutView(LayoutRecord layout,
            List<MetricDefinitionRecord> metrics, Map<String, VisibilityRecord> visibility) {
        Map<String, Object> result = layoutView(layout);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        Set<String> present = new HashSet<>();
        for (Map<String, Object> item : items) present.add(String.valueOf(item.get("widgetId")));
        for (MetricDefinitionRecord metric : metrics) {
            VisibilityRecord visible = visibility == null ? null : visibility.get(metric.id);
            String widgetId = "metric:" + metric.id;
            if (visible == null || !visible.dashboardEnabled || present.contains(widgetId)) continue;
            int[] position = firstFreePosition(items);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("widgetId", widgetId); item.put("x", position[0]); item.put("y", position[1]);
            item.put("w", 3); item.put("h", 2); item.put("minW", 3); item.put("minH", 2);
            item.put("maxW", 12); item.put("maxH", 6); item.put("persisted", false);
            items.add(item); present.add(widgetId);
        }
        return result;
    }

    private static int[] firstFreePosition(List<Map<String, Object>> items) {
        for (int y = 0; y < 10000; y++) for (int x = 0; x <= 9; x++) {
            boolean free = true;
            for (Map<String, Object> item : items) {
                int ix = ((Number) item.get("x")).intValue(); int iy = ((Number) item.get("y")).intValue();
                int iw = ((Number) item.get("w")).intValue(); int ih = ((Number) item.get("h")).intValue();
                if (x < ix + iw && ix < x + 3 && y < iy + ih && iy < y + 2) { free = false; break; }
            }
            if (free) return new int[]{x, y};
        }
        return new int[]{0, 10000};
    }

    private static Map<String, Object> periodView(ResolvedPeriod period) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("granularity", period.getRequested().getGranularity().name());
        value.put("anchor", period.getRequested().getAnchor().toString());
        value.put("start", period.getStart().toString()); value.put("endExclusive", period.getEndExclusive().toString());
        value.put("asOf", period.getAsOf() == null ? null : period.getAsOf().toString());
        value.put("label", period.getStart().toString()); value.put("status", period.getStatus().name());
        return value;
    }

    private static Map<String, Object> previousPeriodView(ResolvedPeriod period) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("start", period.getPreviousStart().toString()); value.put("endExclusive", period.getPreviousEndExclusive().toString());
        return value;
    }

    private static Map<String, Object> comparisonWindowView(ResolvedPeriod period) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("start", period.getComparisonStart().toString()); value.put("endExclusive", period.getComparisonEndExclusive().toString());
        return value;
    }

    private MetricApiResult metricSuccess(int status, Map<String, Object> data, long dataRevision,
            String etag, String location) throws MetricException {
        Map<String, Object> body = new LinkedHashMap<>(); body.put("data", data);
        body.put("meta", Collections.singletonMap("dataRevision", dataRevision));
        try { return new MetricApiResult(status, objectMapper.writeValueAsString(body), etag, location); }
        catch (JsonProcessingException ex) { throw metricInternal(); }
    }

    private DashboardApiResult dashboardSuccess(int status, Map<String, Object> data, long dataRevision,
            String etag, String location) throws DashboardException {
        Map<String, Object> body = new LinkedHashMap<>(); body.put("data", data);
        body.put("meta", Collections.singletonMap("dataRevision", dataRevision));
        try { return new DashboardApiResult(status, objectMapper.writeValueAsString(body), etag, location); }
        catch (JsonProcessingException ex) { throw dashboardInternal(); }
    }

    private static String quoteEtag(long revision) { return "\"" + revision + "\""; }
    private static MetricException metricValidation(String field, String message) {
        return new MetricException(400, "VALIDATION_FAILED", "请求参数无效。", Collections.singletonMap(field, message), Collections.emptyMap());
    }
    private static MetricException metricUnavailable() { return new MetricException(503, "DATABASE_BUSY", "本地数据暂时不可用。"); }
    private static MetricException metricInternal() { return new MetricException(500, "INTERNAL_ERROR", "本地指标服务发生内部错误。"); }
    private static DashboardException dashboardUnavailable() { return new DashboardException(503, "DATABASE_BUSY", "本地数据暂时不可用。"); }
    private static DashboardException dashboardInternal() { return new DashboardException(500, "INTERNAL_ERROR", "本地总览服务发生内部错误。"); }

    private static DashboardException dashboardValidationException(String field, String message) {
        return new DashboardException(400, "VALIDATION_FAILED", "请求参数无效。",
                Collections.singletonMap(field, message), Collections.emptyMap());
    }

    private void validateDashboardMutation(DashboardMutation mutation, String method, String path)
            throws DashboardException {
        if (mutation == null || mutation.getIdempotencyKey() == null || mutation.getIdempotencyKey().trim().isEmpty()) {
            throw dashboardValidationException("Idempotency-Key", "缺少幂等键。");
        }
        if (!method.equals(mutation.getHttpMethod()) || !path.equals(mutation.getCanonicalPath())
                || mutation.getRequestHash() == null || mutation.getRequestHash().trim().isEmpty()) {
            throw dashboardValidationException("request", "请求幂等信息无效。");
        }
    }

    private static void validateLayoutItems(List<LayoutItemRecord> items) throws DashboardException {
        Set<String> ids = new HashSet<>();
        for (LayoutItemRecord item : items) {
            if (item == null || item.widgetId == null || !ids.add(item.widgetId)
                    || !item.widgetId.startsWith("metric:") || item.x < 0 || item.y < 0
                    || item.w < 1 || item.h < 1 || item.x + item.w > 12 || item.w > 12 || item.h > 6
                    || item.minW < 1 || item.minH < 1 || item.maxW < item.minW || item.maxH < item.minH
                    || item.w < item.minW || item.w > item.maxW || item.h < item.minH || item.h > item.maxH) {
                throw new DashboardException(409, "LAYOUT_INVALID", "总览布局无效。",
                        Collections.emptyMap(), Collections.singletonMap("reason", "OUT_OF_BOUNDS"));
            }
        }
        for (int i = 0; i < items.size(); i++) for (int j = i + 1; j < items.size(); j++) {
            LayoutItemRecord a = items.get(i), b = items.get(j);
            if (a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h) {
                throw new DashboardException(409, "LAYOUT_INVALID", "总览布局存在重叠。",
                        Collections.emptyMap(), Collections.singletonMap("reason", "OVERLAP"));
            }
        }
    }

    private static void validateEnabledLayoutItems(List<LayoutItemRecord> items,
            List<MetricDefinitionRecord> metrics, Map<String, VisibilityRecord> visibility)
            throws DashboardException {
        Set<String> enabled = new HashSet<>();
        for (MetricDefinitionRecord metric : metrics) {
            VisibilityRecord visible = visibility.get(metric.id);
            if (visible != null && visible.dashboardEnabled) enabled.add("metric:" + metric.id);
        }
        Set<String> submitted = new HashSet<>();
        for (LayoutItemRecord item : items) submitted.add(item.widgetId);
        if (!submitted.equals(enabled)) {
            throw new DashboardException(409, "LAYOUT_INVALID", "总览布局未覆盖全部启用指标。",
                    Collections.emptyMap(), Collections.singletonMap("reason", "WIDGET_NOT_ENABLED"));
        }
    }

    private static List<LayoutItemRecord> defaultLayoutItems(List<MetricDefinitionRecord> metrics,
            List<VisibilityRecord> visibility) {
        Map<String, VisibilityRecord> byId = visibilityById(visibility);
        List<LayoutItemRecord> result = new ArrayList<>(); int index = 0;
        for (MetricDefinitionRecord metric : metrics) {
            VisibilityRecord visible = byId.get(metric.id);
            if (visible == null || !visible.dashboardEnabled || metric.archivedAt != null) continue;
            int x = (index % 4) * 3, y = (index / 4) * 2;
            result.add(new LayoutItemRecord("metric:" + metric.id, x, y, 3, 2, 3, 2, 12, 6)); index++;
        }
        return result;
    }

    private DashboardApiResult replayDashboard(Connection connection, DashboardMutation mutation, Instant now)
            throws PersistenceException, DashboardException {
        LedgerOperation operation = settings.findOperation(connection, mutation.getIdempotencyKey(), now);
        if (operation == null) return null;
        if (!operation.getHttpMethod().equals(mutation.getHttpMethod())
                || !operation.getCanonicalPath().equals(mutation.getCanonicalPath())
                || !operation.getRequestHash().equals(mutation.getRequestHash())) {
            throw new DashboardException(409, "IDEMPOTENCY_CONFLICT", "幂等键已用于不同请求。");
        }
        try {
            JsonNode body = objectMapper.readTree(operation.getResponseJson());
            long revision = body.at("/data/layout/revision").asLong(-1L);
            return new DashboardApiResult(operation.getResponseStatus(), operation.getResponseJson(),
                    revision < 0 ? null : quoteEtag(revision), null);
        } catch (IOException ex) { throw dashboardInternal(); }
    }

    private LedgerOperation dashboardOperation(DashboardMutation mutation, DashboardApiResult result,
            String timestamp, Instant now) {
        return new LedgerOperation(mutation.getIdempotencyKey(), mutation.getHttpMethod(), mutation.getCanonicalPath(),
                mutation.getRequestHash(), result.getStatus(), result.getResponseJson(), activeContext.snapshot.getProfileId(),
                timestamp, now.plus(OPERATION_RETENTION).toString());
    }

    private static DashboardException dashboardRevision(long currentRevision) {
        return new DashboardException(409, "REVISION_CONFLICT", "总览布局版本已变化。", Collections.emptyMap(),
                Collections.singletonMap("currentRevision", currentRevision));
    }

    private static Map<String, Object> meta(Long dataRevision, Long catalogRevision) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("dataRevision", dataRevision);
        if (catalogRevision != null) {
            meta.put("catalogRevision", catalogRevision);
        }
        return meta;
    }

    private static String etag(CatalogProfile profile) {
        return "\"" + profile.getRevision() + "\"";
    }

    private static final class ActiveProfileContext {
        private final BootstrapSnapshot snapshot;
        private final Path ledgerFile;

        private ActiveProfileContext(BootstrapSnapshot snapshot, Path ledgerFile) {
            this.snapshot = snapshot;
            this.ledgerFile = ledgerFile;
        }
    }

    private static final class BackupContext {
        private final BootstrapSnapshot snapshot;
        private final String profileId;
        private final Path ledgerFile;

        private BackupContext(BootstrapSnapshot snapshot, Path ledgerFile) {
            this.snapshot = snapshot;
            this.profileId = snapshot.getProfileId();
            this.ledgerFile = ledgerFile;
        }
    }

    private static final class BackupCursor {
        private final String createdAt;
        private final String id;

        private BackupCursor(String createdAt, String id) {
            this.createdAt = createdAt;
            this.id = id;
        }

        private static String encode(String profileId, BackupArtifact artifact, ObjectMapper mapper)
                throws BackupException {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("v", 1);
            value.put("profileId", profileId);
            value.put("createdAt", nullToEmpty(artifact.getCreatedAt()));
            value.put("id", artifact.getId());
            try {
                return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(value));
            } catch (JsonProcessingException ex) {
                throw new BackupException(500, "INTERNAL_ERROR", "本地服务发生内部错误。");
            }
        }

        private static BackupCursor parse(String encoded, String profileId, ObjectMapper mapper)
                throws BackupException {
            try {
                JsonNode value = mapper.readTree(Base64.getUrlDecoder().decode(encoded));
                if (value == null || !value.isObject() || value.size() != 4 || value.path("v").asInt(-1) != 1
                        || !profileId.equals(value.path("profileId").asText(null))
                        || !value.path("createdAt").isTextual() || !value.path("id").isTextual()
                        || !isBackupUuid(value.path("id").textValue())) {
                    throw backupValidation("cursor", "游标格式无效或不属于当前用户空间。");
                }
                return new BackupCursor(value.path("createdAt").textValue(), value.path("id").textValue());
            } catch (BackupException ex) {
                throw ex;
            } catch (RuntimeException | IOException ex) {
                throw backupValidation("cursor", "游标格式无效或不属于当前用户空间。");
            }
        }
    }

    private static final class SetupCommit {
        private final LedgerInitializationApiResult result;
        private final long dataRevision;
        private final String ledgerStartOn;

        private SetupCommit(LedgerInitializationApiResult result, long dataRevision, String ledgerStartOn) {
            this.result = result;
            this.dataRevision = dataRevision;
            this.ledgerStartOn = ledgerStartOn;
        }
    }

    private static final class SetupFacts {
        private final String setupState;
        private final String ledgerStartOn;
        private final Map<String, SetupAccount> accounts;
        private final SetupAccount defaultAccount;
        private final LocalDate suggestedStart;

        private SetupFacts(String setupState, String ledgerStartOn, Map<String, SetupAccount> accounts,
                SetupAccount defaultAccount, LocalDate suggestedStart) {
            this.setupState = setupState;
            this.ledgerStartOn = ledgerStartOn;
            this.accounts = accounts;
            this.defaultAccount = defaultAccount;
            this.suggestedStart = suggestedStart;
        }
    }

    private static final class SetupAccount {
        private final String id;
        private final String name;
        private final LocalDate openingOn;
        private final long openingBalanceMinor;
        private final boolean system;
        private final String archivedAt;
        private final long revision;
        private final String earliestSettlementOn;

        private SetupAccount(String id, String name, LocalDate openingOn, long openingBalanceMinor,
                boolean system, String archivedAt, long revision, String earliestSettlementOn) {
            this.id = id;
            this.name = name;
            this.openingOn = openingOn;
            this.openingBalanceMinor = openingBalanceMinor;
            this.system = system;
            this.archivedAt = archivedAt;
            this.revision = revision;
            this.earliestSettlementOn = earliestSettlementOn;
        }
    }

    private static final class CategoryCommit {
        private final CategoryApiResult result;
        private final long dataRevision;

        private CategoryCommit(CategoryApiResult result, long dataRevision) {
            this.result = result;
            this.dataRevision = dataRevision;
        }
    }

    private static final class AccountCommit {
        private final AccountApiResult result; private final long dataRevision;
        private AccountCommit(AccountApiResult result,long dataRevision){this.result=result;this.dataRevision=dataRevision;}
    }

    private static final class MutationCommit {
        private final ProfileApiResult result;
        private final ActiveProfileContext context;

        private MutationCommit(ProfileApiResult result, ActiveProfileContext context) {
            this.result = result;
            this.context = context;
        }
    }

    private static final class SettingsCommit {
        private final SettingsApiResult result;
        private final ActiveProfileContext context;

        private SettingsCommit(SettingsApiResult result, ActiveProfileContext context) {
            this.result = result;
            this.context = context;
        }
    }

    private static final class ProfileView {
        private final CatalogProfile profile;
        private final String status;

        private ProfileView(CatalogProfile profile, String status) {
            this.profile = profile;
            this.status = status;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", profile.getId());
            value.put("name", profile.getName());
            value.put("status", status);
            value.put("createdAt", profile.getCreatedAt());
            value.put("lastOpenedAt", profile.getLastOpenedAt());
            value.put("revision", profile.getRevision());
            return value;
        }

        private int sortRank() {
            return "ACTIVE".equals(status) ? 0 : "INACTIVE".equals(status) ? 1 : 2;
        }

        private String getId() { return profile.getId(); }
        private String getLastOpenedAt() { return profile.getLastOpenedAt(); }
    }

    private static final class Cursor {
        private final boolean includeArchived;
        private final long catalogRevision;
        private final int sortRank;
        private final String lastOpenedAt;
        private final String id;

        private Cursor(boolean includeArchived, long catalogRevision, int sortRank, String lastOpenedAt, String id) {
            this.includeArchived = includeArchived;
            this.catalogRevision = catalogRevision;
            this.sortRank = sortRank;
            this.lastOpenedAt = lastOpenedAt;
            this.id = id;
        }

        private static Cursor from(ProfileView view, boolean includeArchived, long catalogRevision) {
            return new Cursor(includeArchived, catalogRevision, view.sortRank(), view.getLastOpenedAt(), view.getId());
        }

        private String encode() {
            String payload = "v1\n" + includeArchived + "\n" + catalogRevision + "\n" + sortRank + "\n"
                    + lastOpenedAt + "\n" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        private static Cursor parse(String encoded, boolean includeArchived, long catalogRevision) throws ProfileException {
            try {
                String payload = new String(Base64.getUrlDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
                String[] fields = payload.split("\\n", -1);
                if (fields.length != 6 || !"v1".equals(fields[0])
                        || !Boolean.toString(includeArchived).equals(fields[1])
                        || Long.parseLong(fields[2]) != catalogRevision
                        || Integer.parseInt(fields[3]) < 0 || Integer.parseInt(fields[3]) > 2
                        || !isLowercaseUuid(fields[5])) {
                    throw validation("cursor", "游标无效或已过期。");
                }
                Instant.parse(fields[4]);
                return new Cursor(includeArchived, catalogRevision, Integer.parseInt(fields[3]), fields[4], fields[5]);
            } catch (IllegalArgumentException | java.time.DateTimeException ex) {
                throw validation("cursor", "游标无效或已过期。");
            }
        }
    }

    private static final class RecordCommit {
        private final RecordApiResult result; private final long dataRevision;
        private RecordCommit(RecordApiResult result,long dataRevision){this.result=result;this.dataRevision=dataRevision;}
    }

    private static final class MetricCommit {
        private final MetricApiResult result; private final long dataRevision;
        private MetricCommit(MetricApiResult result, long dataRevision) {
            this.result = result; this.dataRevision = dataRevision;
        }
    }

    private static final class DashboardCommit {
        private final DashboardApiResult result; private final long dataRevision;
        private DashboardCommit(DashboardApiResult result, long dataRevision) {
            this.result = result; this.dataRevision = dataRevision;
        }
    }
}
