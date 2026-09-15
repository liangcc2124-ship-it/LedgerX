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

/** A single runtime facade so status and profile REST routes share one active-profile context. */
public final class ApplicationRuntime implements SystemStatusProvider, ProfileApi, SettingsApi, CategoryApi, AccountApi, RecordApi {
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
}
