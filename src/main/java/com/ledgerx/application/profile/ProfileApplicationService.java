package com.ledgerx.application.profile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.application.system.SystemStatus;
import com.ledgerx.application.system.SystemStatusProvider;
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
import com.ledgerx.persistence.BootstrapSnapshot;
import com.ledgerx.persistence.LedgerSettingsRepository;
import com.ledgerx.persistence.LedgerSettingsRepository.LedgerOperation;
import com.ledgerx.persistence.LedgerSettingsRepository.SettingsRecord;
import com.ledgerx.persistence.LedgerSettingsRepository.SettingsState;
import com.ledgerx.persistence.LedgerCatalogRepository;
import com.ledgerx.persistence.LedgerCatalogRepository.CategoryRecord;
import com.ledgerx.persistence.LedgerCatalogRepository.AccountRecord;
import com.ledgerx.persistence.LedgerCatalogRepository.RecordRecord;
import com.ledgerx.persistence.LedgerBootstrap;
import com.ledgerx.persistence.PersistenceException;
import com.ledgerx.persistence.ProfileBootstrap;
import com.ledgerx.persistence.ProfileCatalogRepository;
import com.ledgerx.persistence.ProfileCatalogRepository.CatalogOperation;
import com.ledgerx.persistence.ProfileCatalogRepository.CatalogProfile;
import com.ledgerx.persistence.ProfileCatalogRepository.CatalogState;
import com.ledgerx.persistence.SqliteDatabase;
import com.ledgerx.persistence.TransactionRunner;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The sole owner of the active profile identity.  Catalog writes hold one
 * process-local exclusive gate so the system-status projection and profile
 * operations cannot observe different active profile snapshots.
 */
public final class ProfileApplicationService implements ProfileApi, SettingsApi, CategoryApi, AccountApi, RecordApi, SystemStatusProvider {
    private static final Duration OPERATION_RETENTION = Duration.ofDays(7);
    private static final String API_VERSION = "1.0";
    private static final int BACKUP_FORMAT_VERSION = 3;

    private final String applicationVersion;
    private final Clock clock;
    private final ProfileBootstrap profileBootstrap;
    private final LedgerBootstrap ledgerBootstrap;
    private final ProfileCatalogRepository catalog;
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
        this.activeContext = initialSnapshot == null ? null : new ActiveProfileContext(initialSnapshot,
                profileBootstrap.getDataRoot().resolve("Profiles").resolve(initialSnapshot.getProfileId())
                        .resolve("ledger.db").toAbsolutePath().normalize());
    }

    public static ProfileApplicationService open(Path dataRoot, String applicationVersion, Clock clock)
            throws PersistenceException {
        if (applicationVersion == null || applicationVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("applicationVersion is required");
        }
        Clock actualClock = clock == null ? Clock.systemUTC() : clock;
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
        Clock actualClock = clock == null ? Clock.systemUTC() : clock;
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
            return new SystemStatus(
                    API_VERSION,
                    applicationVersion,
                    snapshot.getSchemaVersion(),
                    BACKUP_FORMAT_VERSION,
                    snapshot.getProfileId(),
                    "READY",
                    Arrays.asList("system.status", "profiles.read", "profiles.write", "settings.read", "settings.write",
                            "categories.read", "categories.write", "accounts.read", "accounts.write",
                            "records.read", "records.write"),
                    snapshot.getDataRevision());
        } finally {
            gate.readLock().unlock();
        }
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
                                nextDataRevision), activeContext.ledgerFile);
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
        if (asOf == null) asOf = LocalDate.now(clock);
        gate.readLock().lock();
        try { requireAccountContext(); LedgerCatalogRepository repo = accountRepository();
            try(Connection c=repo.openConnection()){List<AccountRecord> rows=repo.listAccounts(c,includeArchived,asOf);long revision=repo.dataRevision(c);int start=accountCursorStart(rows,cursor,includeArchived,asOf,revision);int end=Math.min(rows.size(),start+limit);List<Map<String,Object>> items=new ArrayList<>();for(int i=start;i<end;i++)items.add(accountMap(rows.get(i)));Map<String,Object> page=new LinkedHashMap<>();page.put("nextCursor",end<rows.size()?accountCursor(rows.get(end-1),includeArchived,asOf,revision):null);page.put("hasMore",end<rows.size());page.put("limit",limit);Map<String,Object> data=new LinkedHashMap<>();data.put("items",items);data.put("page",page);return accountSuccess(200,data,revision,"\""+revision+"\"",null);}
        } catch(AccountException ex){throw ex;} catch(PersistenceException|SQLException ex){throw accountUnavailable();} finally{gate.readLock().unlock();}
    }

    @Override
    public AccountApiResult createAccount(AccountPatch patch, AccountMutation mutation) throws AccountException {
        requireAccountContext(); validateAccountMutation(mutation,"POST","/api/v1/accounts"); if(patch==null)throw accountValidation("body","请求体必须是对象。");validateAccountId(patch.getId());validateAccountName(patch.getName());validateAccountKind(patch.getKind(),patch.isIncludeInAvailableCash());
        gate.writeLock().lock(); try(Connection c=accountRepository().openConnection()){AccountCommit commit=transactions.write(c,tx->{Instant now=Instant.now(clock);String t=now.toString();LedgerCatalogRepository repo=accountRepository();settings.removeExpiredOperations(tx,now);AccountApiResult replay=replayAccount(tx,mutation,now);if(replay!=null)return new AccountCommit(replay,-1);AccountRecord a=new AccountRecord(patch.getId(),patch.getName().trim(),patch.getKind(),kindSide(patch.getKind()),patch.getOpeningOn(),patch.getOpeningBalanceMinor(),patch.isIncludeInAvailableCash(),false,null,t,t,0,patch.getOpeningBalanceMinor());try{repo.insertAccount(tx,a,t);}catch(SQLException ex){throw accountReference("ACCOUNT_NAME_CONFLICT");}long rev=repo.dataRevision(tx);settings.advanceLedgerMeta(tx,rev,t);AccountRecord stored=repo.findAccount(tx,patch.getId(),LocalDate.now(clock));AccountApiResult result=accountSuccess(201,singletonAccountData(stored),rev+1,"\"0\"","/api/v1/accounts/"+patch.getId());settings.insertOperation(tx,new LedgerOperation(mutation.getIdempotencyKey(),mutation.getHttpMethod(),mutation.getCanonicalPath(),mutation.getRequestHash(),result.getStatus(),result.getResponseJson(),activeContext.snapshot.getProfileId(),t,now.plus(OPERATION_RETENTION).toString()));return new AccountCommit(result,rev+1);});refreshAccountContext(commit.dataRevision);return commit.result;}catch(AccountException ex){throw ex;}catch(PersistenceException|SQLException ex){throw accountUnavailable();}catch(Exception ex){throw accountInternal();}finally{gate.writeLock().unlock();}
    }

    @Override
    public AccountApiResult replaceAccount(String id,long expectedRevision,AccountPatch patch,AccountMutation mutation)throws AccountException{
        requireAccountContext();validateAccountId(id);validateAccountMutation(mutation,"PUT","/api/v1/accounts/"+id);if(patch==null)throw accountValidation("body","请求体必须是对象。");validateAccountName(patch.getName());validateAccountKind(patch.getKind(),patch.isIncludeInAvailableCash());
        gate.writeLock().lock();try(Connection c=accountRepository().openConnection()){AccountCommit commit=transactions.write(c,tx->{Instant now=Instant.now(clock);String t=now.toString();LedgerCatalogRepository repo=accountRepository();settings.removeExpiredOperations(tx,now);AccountApiResult replay=replayAccount(tx,mutation,now);if(replay!=null)return new AccountCommit(replay,-1);AccountRecord current=repo.findAccount(tx,id,LocalDate.now(clock));if(current==null)throw accountNotFound();if(current.archivedAt!=null)throw accountReference("ACCOUNT_NOT_ACTIVE");requireAccountRevision(current,expectedRevision);String earliest=repo.earliestSettlement(tx,id);if(earliest!=null&&patch.getOpeningOn().toString().compareTo(earliest)>0)throw new AccountException(409,"REFERENCE_CONFLICT","期初日不能晚于已有结算日。",Collections.emptyMap(),Collections.singletonMap("earliestSettlementOn",earliest));AccountRecord replacement=new AccountRecord(id,patch.getName().trim(),patch.getKind(),kindSide(patch.getKind()),patch.getOpeningOn(),patch.getOpeningBalanceMinor(),patch.isIncludeInAvailableCash(),current.system,current.archivedAt,current.createdAt,t,current.revision+1,patch.getOpeningBalanceMinor());try{repo.updateAccount(tx,id,replacement,current.revision+1,t);}catch(SQLException ex){throw accountReference("ACCOUNT_NAME_CONFLICT");}long rev=repo.dataRevision(tx);settings.advanceLedgerMeta(tx,rev,t);AccountRecord stored=repo.findAccount(tx,id,LocalDate.now(clock));AccountApiResult result=accountSuccess(200,singletonAccountData(stored),rev+1,"\""+stored.revision+"\"",null);settings.insertOperation(tx,new LedgerOperation(mutation.getIdempotencyKey(),mutation.getHttpMethod(),mutation.getCanonicalPath(),mutation.getRequestHash(),result.getStatus(),result.getResponseJson(),activeContext.snapshot.getProfileId(),t,now.plus(OPERATION_RETENTION).toString()));return new AccountCommit(result,rev+1);});refreshAccountContext(commit.dataRevision);return commit.result;}catch(AccountException ex){throw ex;}catch(PersistenceException|SQLException ex){throw accountUnavailable();}catch(Exception ex){throw accountInternal();}finally{gate.writeLock().unlock();}
    }

    @Override
    public AccountApiResult archiveAccount(String id,long expectedRevision,AccountMutation mutation)throws AccountException{
        requireAccountContext();validateAccountId(id);validateAccountMutation(mutation,"DELETE","/api/v1/accounts/"+id);gate.writeLock().lock();try(Connection c=accountRepository().openConnection()){AccountCommit commit=transactions.write(c,tx->{Instant now=Instant.now(clock);String t=now.toString();LedgerCatalogRepository repo=accountRepository();settings.removeExpiredOperations(tx,now);AccountApiResult replay=replayAccount(tx,mutation,now);if(replay!=null)return new AccountCommit(replay,-1);AccountRecord current=repo.findAccount(tx,id,LocalDate.now(clock));if(current==null)throw accountNotFound();if(current.system||current.archivedAt!=null)throw accountReference("ACCOUNT_NOT_ARCHIVABLE");requireAccountRevision(current,expectedRevision);repo.archiveAccount(tx,id,current.revision+1,t);long rev=repo.dataRevision(tx);settings.advanceLedgerMeta(tx,rev,t);AccountRecord stored=repo.findAccount(tx,id,LocalDate.now(clock));AccountApiResult result=accountSuccess(200,singletonAccountData(stored),rev+1,"\""+stored.revision+"\"",null);settings.insertOperation(tx,new LedgerOperation(mutation.getIdempotencyKey(),mutation.getHttpMethod(),mutation.getCanonicalPath(),mutation.getRequestHash(),result.getStatus(),result.getResponseJson(),activeContext.snapshot.getProfileId(),t,now.plus(OPERATION_RETENTION).toString()));return new AccountCommit(result,rev+1);});refreshAccountContext(commit.dataRevision);return commit.result;}catch(AccountException ex){throw ex;}catch(PersistenceException|SQLException ex){throw accountUnavailable();}catch(Exception ex){throw accountInternal();}finally{gate.writeLock().unlock();}
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

    private void requireSettingsContext() throws SettingsException {
        if (activeContext == null) {
            throw new SettingsException(423, "RECOVERY_REQUIRED", "数据恢复完成前不能读取或保存设置。");
        }
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
    }

    private void refreshCategoryContext(long dataRevision) {
        if (dataRevision < 0 || activeContext == null) return;
        activeContext = new ActiveProfileContext(
                new BootstrapSnapshot(activeContext.snapshot.getProfileId(), activeContext.snapshot.getSchemaVersion(), dataRevision),
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
    private void requireAccountContext() throws AccountException { if(activeContext==null)throw new AccountException(423,"RECOVERY_REQUIRED","数据恢复完成前不能操作账户。"); }
    private void refreshAccountContext(long revision){if(revision<0||activeContext==null)return;activeContext=new ActiveProfileContext(new BootstrapSnapshot(activeContext.snapshot.getProfileId(),activeContext.snapshot.getSchemaVersion(),revision),activeContext.ledgerFile);}
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
    private static String kindSide(String kind){return Arrays.asList("CREDIT","LOAN","OTHER_LIABILITY").contains(kind)?"LIABILITY":"ASSET";}
    private static void requireAccountRevision(AccountRecord a,long expected)throws AccountException{if(a.revision!=expected)throw new AccountException(409,"REVISION_CONFLICT","账户已被更新。",Collections.emptyMap(),Collections.singletonMap("currentRevision",a.revision));}
    private static AccountException accountValidation(String field,String message){return new AccountException(400,"VALIDATION_FAILED","请求参数无效。",Collections.singletonMap(field,message),Collections.emptyMap());}
    private static AccountException accountNotFound(){return new AccountException(404,"NOT_FOUND","账户不存在。");}
    private static AccountException accountReference(String reason){return new AccountException(409,"REFERENCE_CONFLICT","账户状态冲突。",Collections.emptyMap(),Collections.singletonMap("reason",reason));}
    private static AccountException accountUnavailable(){return new AccountException(503,"SERVICE_UNAVAILABLE","本地数据暂时不可用。");}
    private static AccountException accountInternal(){return new AccountException(500,"INTERNAL_ERROR","本地服务发生内部错误。");}

    private LedgerCatalogRepository recordRepository() throws RecordException { requireRecordContext(); return new LedgerCatalogRepository(activeContext.ledgerFile); }
    private void requireRecordContext() throws RecordException { if(activeContext==null)throw new RecordException(423,"RECOVERY_REQUIRED","数据恢复完成前不能操作记录。"); }
    private void refreshRecordContext(long revision){if(revision>=0&&activeContext!=null)activeContext=new ActiveProfileContext(new BootstrapSnapshot(activeContext.snapshot.getProfileId(),activeContext.snapshot.getSchemaVersion(),revision),activeContext.ledgerFile);}
    private static boolean isBasicRecord(String type){return "INCOME".equals(type)||"FIXED_COST".equals(type)||"VARIABLE_COST".equals(type);}
    private static void validateRecordId(String id)throws RecordException{if(!isLowercaseUuid(id))throw recordValidation("id","必须是小写 UUID。");}
    private static void validateRecordPatch(RecordPatch p,boolean create)throws RecordException{if(p==null)throw recordValidation("body","请求体必须是对象。");if(create)validateRecordId(p.getId());if(p.getType()==null)throw recordValidation("recordType","记录类型无效。");if(p.getAmountMinor()<=0)throw recordValidation("amount","金额必须大于 0。");if(p.getOccurredOn()==null)throw recordValidation("occurredOn","日期不能为空。");if(p.getSettlementOn()==null)throw recordValidation("settlement.settlementOn","日期不能为空。");if(p.getNote()==null||p.getNote().codePointCount(0,p.getNote().length())>4000)throw recordValidation("note","备注长度不能超过 4000 个字符。");validateRecordId(p.getCategoryId());validateRecordId(p.getAccountId());}
    private void validateRecordReferences(Connection c,RecordPatch p)throws RecordException,SQLException{LedgerCatalogRepository repo=recordRepository();CategoryRecord category=repo.findCategory(c,p.getCategoryId());if(category==null||category.archivedAt!=null)throw recordReference("CATEGORY_NOT_ACTIVE");if(category.system&&!repo.recordTypes(c,p.getCategoryId()).contains(p.getType().name()))throw recordReference("CATEGORY_RECORD_TYPE_MISMATCH");AccountRecord account=repo.findAccount(c,p.getAccountId(),p.getSettlementOn());if(account==null||account.archivedAt!=null)throw recordReference("ACCOUNT_NOT_ACTIVE");if(p.getSettlementOn().isBefore(account.openingOn))throw recordReference("SETTLEMENT_BEFORE_OPENING");}
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
}
