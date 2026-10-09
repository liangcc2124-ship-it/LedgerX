package com.ledgerx.application.bootstrap;

import com.ledgerx.application.profile.ProfileApi;
import com.ledgerx.application.profile.ProfileApiResult;
import com.ledgerx.application.profile.ProfileApplicationService;
import com.ledgerx.application.profile.ProfileException;
import com.ledgerx.application.profile.ProfileMutation;
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
import java.time.LocalDate;
import com.ledgerx.application.settings.SettingsApi;
import com.ledgerx.application.settings.SettingsApiResult;
import com.ledgerx.application.settings.SettingsException;
import com.ledgerx.application.settings.SettingsMutation;
import com.ledgerx.application.settings.SettingsPatch;
import com.ledgerx.application.system.SystemStatus;
import com.ledgerx.application.system.SystemStatusProvider;
import com.ledgerx.application.metrics.DashboardApi;
import com.ledgerx.application.metrics.DashboardApiResult;
import com.ledgerx.application.metrics.DashboardException;
import com.ledgerx.application.metrics.DashboardLayoutDraft;
import com.ledgerx.application.metrics.DashboardMutation;
import com.ledgerx.application.metrics.MetricApiResult;
import com.ledgerx.application.metrics.MetricDraft;
import com.ledgerx.application.metrics.MetricException;
import com.ledgerx.application.metrics.FormulaDraftRequest;
import com.ledgerx.application.metrics.MetricGranularity;
import com.ledgerx.application.metrics.MetricMutation;
import com.ledgerx.application.metrics.MetricListQuery;
import com.ledgerx.application.metrics.MetricsApi;
import com.ledgerx.application.ledgerinitialization.LedgerInitializationApi;
import com.ledgerx.application.ledgerinitialization.LedgerInitializationApiResult;
import com.ledgerx.application.ledgerinitialization.LedgerInitializationDraft;
import com.ledgerx.application.ledgerinitialization.LedgerInitializationException;
import com.ledgerx.application.ledgerinitialization.LedgerInitializationMutation;
import com.ledgerx.application.backup.BackupApi;
import com.ledgerx.application.backup.BackupApiResult;
import com.ledgerx.application.backup.BackupDownload;
import com.ledgerx.application.backup.BackupException;

/** A single runtime facade so status and profile REST routes share one active-profile context. */
public final class ApplicationRuntime implements SystemStatusProvider, ProfileApi, SettingsApi, CategoryApi, AccountApi,
        RecordApi, MetricsApi, DashboardApi, LedgerInitializationApi, BackupApi {
    private final SystemStatusProvider statusProvider;
    private final ProfileApplicationService profiles;

    private ApplicationRuntime(SystemStatusProvider statusProvider, ProfileApplicationService profiles) {
        this.statusProvider = statusProvider;
        this.profiles = profiles;
    }

    public static ApplicationRuntime ready(ProfileApplicationService profiles) {
        if (profiles == null) {
            throw new IllegalArgumentException("profiles is required");
        }
        return new ApplicationRuntime(profiles, profiles);
    }

    public static ApplicationRuntime unavailable(SystemStatusProvider statusProvider) {
        return unavailable(statusProvider, null);
    }

    public static ApplicationRuntime unavailable(SystemStatusProvider statusProvider, ProfileApplicationService profiles) {
        if (statusProvider == null) {
            throw new IllegalArgumentException("statusProvider is required");
        }
        return new ApplicationRuntime(statusProvider, profiles);
    }

    @Override
    public SystemStatus current() {
        return statusProvider.current();
    }

    @Override
    public ProfileApiResult list(boolean includeArchived, int limit, String cursor) throws ProfileException {
        return requireProfiles().list(includeArchived, limit, cursor);
    }

    @Override
    public ProfileApiResult create(String id, String name, ProfileMutation mutation) throws ProfileException {
        return requireProfiles().create(id, name, mutation);
    }

    @Override
    public ProfileApiResult activate(String id, long expectedRevision, ProfileMutation mutation) throws ProfileException {
        return requireProfiles().activate(id, expectedRevision, mutation);
    }

    @Override
    public ProfileApiResult archive(String id, long expectedRevision, ProfileMutation mutation) throws ProfileException {
        return requireProfiles().archive(id, expectedRevision, mutation);
    }

    @Override
    public ProfileApiResult findOperation(String idempotencyKey) throws ProfileException {
        return requireProfiles().findOperation(idempotencyKey);
    }

    @Override
    public SettingsApiResult read() throws SettingsException {
        return requireSettings().read();
    }

    @Override
    public SettingsApiResult update(long expectedRevision, SettingsPatch patch, SettingsMutation mutation)
            throws SettingsException {
        return requireSettings().update(expectedRevision, patch, mutation);
    }

    @Override
    public CategoryApiResult listCategories(boolean includeArchived, int limit, String cursor) throws CategoryException {
        return requireCategories().listCategories(includeArchived, limit, cursor);
    }

    @Override
    public CategoryApiResult createCategory(CategoryPatch patch, CategoryMutation mutation) throws CategoryException {
        return requireCategories().createCategory(patch, mutation);
    }

    @Override
    public CategoryApiResult replaceCategory(String id, long expectedRevision, CategoryPatch patch,
            CategoryMutation mutation) throws CategoryException {
        return requireCategories().replaceCategory(id, expectedRevision, patch, mutation);
    }

    @Override
    public CategoryApiResult archiveCategory(String id, long expectedRevision, CategoryMutation mutation)
            throws CategoryException {
        return requireCategories().archiveCategory(id, expectedRevision, mutation);
    }

    @Override
    public CategoryApiResult mergeCategories(String targetId, long expectedRevision, String[] sourceIds,
            long[] sourceRevisions, CategoryMutation mutation) throws CategoryException {
        return requireCategories().mergeCategories(targetId, expectedRevision, sourceIds, sourceRevisions, mutation);
    }

    @Override
    public AccountApiResult listAccounts(boolean includeArchived, LocalDate asOf, int limit, String cursor) throws AccountException {
        return requireAccounts().listAccounts(includeArchived, asOf, limit, cursor);
    }
    @Override
    public AccountApiResult createAccount(AccountPatch patch, AccountMutation mutation) throws AccountException {
        return requireAccounts().createAccount(patch, mutation);
    }
    @Override
    public AccountApiResult replaceAccount(String id, long expectedRevision, AccountPatch patch, AccountMutation mutation) throws AccountException {
        return requireAccounts().replaceAccount(id, expectedRevision, patch, mutation);
    }
    @Override
    public AccountApiResult archiveAccount(String id, long expectedRevision, AccountMutation mutation) throws AccountException {
        return requireAccounts().archiveAccount(id, expectedRevision, mutation);
    }

    @Override
    public RecordApiResult listRecords(String status, int limit, String cursor) throws RecordException { return requireRecords().listRecords(status, limit, cursor); }
    @Override
    public RecordApiResult getRecord(String id) throws RecordException { return requireRecords().getRecord(id); }
    @Override
    public RecordApiResult createRecord(RecordPatch patch, RecordMutation mutation) throws RecordException { return requireRecords().createRecord(patch, mutation); }
    @Override
    public RecordApiResult replaceRecord(String id, long expectedRevision, RecordPatch patch, RecordMutation mutation) throws RecordException { return requireRecords().replaceRecord(id, expectedRevision, patch, mutation); }
    @Override
    public RecordApiResult trashRecord(String id, long expectedRevision, RecordMutation mutation) throws RecordException { return requireRecords().trashRecord(id, expectedRevision, mutation); }
    @Override
    public RecordApiResult restoreRecord(String id, long expectedRevision, RecordMutation mutation) throws RecordException { return requireRecords().restoreRecord(id, expectedRevision, mutation); }

    @Override
    public MetricApiResult listMetrics(MetricListQuery query) throws MetricException {
        return requireMetrics().listMetrics(query);
    }

    @Override
    public MetricApiResult getMetric(String id, boolean includeArchived) throws MetricException {
        return requireMetrics().getMetric(id, includeArchived);
    }

    @Override
    public MetricApiResult createMetric(MetricDraft draft, MetricMutation mutation) throws MetricException {
        return requireMetrics().createMetric(draft, mutation);
    }

    @Override
    public MetricApiResult updateMetric(String id, long expectedRevision, MetricDraft draft, MetricMutation mutation)
            throws MetricException {
        return requireMetrics().updateMetric(id, expectedRevision, draft, mutation);
    }

    @Override
    public MetricApiResult updateSystemVisibility(String id, long expectedRevision, boolean hidden,
            boolean dashboardEnabled, MetricMutation mutation) throws MetricException {
        return requireMetrics().updateSystemVisibility(id, expectedRevision, hidden, dashboardEnabled, mutation);
    }

    @Override
    public MetricApiResult archiveMetric(String id, long expectedRevision, MetricMutation mutation)
            throws MetricException {
        return requireMetrics().archiveMetric(id, expectedRevision, mutation);
    }

    @Override
    public MetricApiResult validateFormula(FormulaDraftRequest draft) throws MetricException {
        return requireMetrics().validateFormula(draft);
    }

    @Override
    public MetricApiResult previewFormula(FormulaDraftRequest draft) throws MetricException {
        return requireMetrics().previewFormula(draft);
    }

    @Override
    public MetricApiResult listFormulaVersions(String formulaId, int limit, String cursor)
            throws MetricException {
        return requireMetrics().listFormulaVersions(formulaId, limit, cursor);
    }

    @Override
    public DashboardApiResult readDashboard(MetricGranularity granularity, LocalDate anchor)
            throws DashboardException {
        return requireDashboard().readDashboard(granularity, anchor);
    }

    @Override
    public DashboardApiResult readLayout() throws DashboardException {
        return requireDashboard().readLayout();
    }

    @Override
    public DashboardApiResult replaceLayout(long expectedRevision, DashboardLayoutDraft draft,
            DashboardMutation mutation) throws DashboardException {
        return requireDashboard().replaceLayout(expectedRevision, draft, mutation);
    }

    @Override
    public DashboardApiResult resetLayout(long expectedRevision, DashboardMutation mutation) throws DashboardException {
        return requireDashboard().resetLayout(expectedRevision, mutation);
    }

    @Override
    public LedgerInitializationApiResult readInitialization() throws LedgerInitializationException {
        return requireInitialization().readInitialization();
    }

    @Override
    public LedgerInitializationApiResult initializeLedger(LedgerInitializationDraft draft,
            LedgerInitializationMutation mutation) throws LedgerInitializationException {
        return requireInitialization().initializeLedger(draft, mutation);
    }

    @Override
    public BackupApiResult createBackup(String idempotencyKey) throws BackupException {
        return requireBackups().createBackup(idempotencyKey);
    }

    @Override
    public BackupApiResult listBackups(int limit, String cursor) throws BackupException {
        return requireBackups().listBackups(limit, cursor);
    }

    @Override
    public BackupApiResult verifyBackup(String id) throws BackupException {
        return requireBackups().verifyBackup(id);
    }

    @Override
    public BackupDownload openBackupDownload(String id) throws BackupException {
        return requireBackups().openBackupDownload(id);
    }

    private ProfileApplicationService requireInitialization() throws LedgerInitializationException {
        if (profiles == null) {
            throw new LedgerInitializationException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能初始化账本。");
        }
        return profiles;
    }

    private ProfileApplicationService requireBackups() throws BackupException {
        if (profiles == null) {
            throw new BackupException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能操作备份。");
        }
        return profiles;
    }

    private ProfileApplicationService requireProfiles() throws ProfileException {
        if (profiles == null) {
            throw new ProfileException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能操作用户空间。");
        }
        return profiles;
    }

    private ProfileApplicationService requireSettings() throws SettingsException {
        if (profiles == null) {
            throw new SettingsException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能读取或保存设置。");
        }
        return profiles;
    }

    private ProfileApplicationService requireCategories() throws CategoryException {
        if (profiles == null) {
            throw new CategoryException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能操作分类。");
        }
        return profiles;
    }

    private ProfileApplicationService requireAccounts() throws AccountException {
        if (profiles == null) throw new AccountException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能操作账户。");
        return profiles;
    }

    private ProfileApplicationService requireRecords() throws RecordException {
        if (profiles == null) throw new RecordException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能操作记录。");
        return profiles;
    }

    private ProfileApplicationService requireMetrics() throws MetricException {
        if (profiles == null) throw new MetricException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能操作指标。");
        return profiles;
    }

    private ProfileApplicationService requireDashboard() throws DashboardException {
        if (profiles == null) throw new DashboardException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能读取总览。");
        return profiles;
    }
}
