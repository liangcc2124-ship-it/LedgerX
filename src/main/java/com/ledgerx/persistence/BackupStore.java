package com.ledgerx.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sqlite.SQLiteConnection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Owns the filesystem and SQLite consistency boundary for manual profile backups. */
public final class BackupStore {
    public static final int FORMAT_VERSION = 3;
    private static final int MANIFEST_LIMIT_BYTES = 64 * 1024;
    private static final long LEDGER_LIMIT_BYTES = 2L * 1024 * 1024 * 1024;
    private static final Pattern UUID_V4 = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final String EXTENSION = ".ledgerx-backup";

    private final Path backupsRoot;
    private final String applicationVersion;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BackupStore(Path dataRoot, String applicationVersion, Clock clock) {
        if (dataRoot == null || applicationVersion == null || applicationVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("data root and application version are required");
        }
        this.backupsRoot = dataRoot.toAbsolutePath().normalize().resolve("Backups").normalize();
        this.applicationVersion = applicationVersion;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public synchronized BackupArtifact create(Path sourceLedger, String profileId, String backupId) throws PersistenceException {
        validateUuid(profileId);
        validateUuid(backupId);
        Path profileDirectory = prepareProfileDirectory(profileId);
        Path published = publishedFile(profileDirectory, backupId);
        if (Files.isRegularFile(published, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(published)) {
            return verify(published, profileId, backupId);
        }
        if (Files.exists(published, LinkOption.NOFOLLOW_LINKS)) {
            throw new PersistenceException("published backup path is unsafe");
        }

        Path staging = profileDirectory.resolve(".staging-" + backupId + "-" + UUID.randomUUID()).normalize();
        Path snapshot = staging.resolve("ledger.db");
        Path packageFile = staging.resolve(backupId + EXTENSION);
        Path extracted = staging.resolve("verified-ledger.db");
        try {
            Files.createDirectory(staging);
            onlineBackup(sourceLedger, snapshot);
            SnapshotFacts facts = inspectSnapshot(snapshot);
            String createdAt = Instant.now(clock).toString();
            String ledgerHash = sha256(snapshot);
            byte[] manifest = manifest(backupId, profileId, createdAt, facts, Files.size(snapshot), ledgerHash);
            writePackage(packageFile, manifest, snapshot);
            forceFile(packageFile);
            BackupArtifact verified = verifyPackage(packageFile, profileId, backupId, extracted, "VALID");
            try {
                Files.move(packageFile, published, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException ex) {
                return verify(published, profileId, backupId);
            } catch (AtomicMoveNotSupportedException ex) {
                throw new PersistenceException("backup volume does not support atomic publication", ex);
            }
            return verified.withPackageSize(Files.size(published));
        } catch (BackupInvalidException | BackupNotFoundException ex) {
            throw ex;
        } catch (PersistenceException ex) {
            throw ex;
        } catch (IOException | SQLException | RuntimeException ex) {
            throw new PersistenceException("backup could not be created", ex);
        } finally {
            deleteKnown(extracted);
            deleteKnown(packageFile);
            deleteKnown(snapshot);
            deleteKnown(staging);
        }
    }

    public BackupArtifact verify(String profileId, String backupId) throws PersistenceException {
        validateUuid(profileId);
        validateUuid(backupId);
        Path profileDirectory = existingProfileDirectory(profileId);
        Path published = publishedFile(profileDirectory, backupId);
        if (!Files.isRegularFile(published, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(published)) {
            throw new BackupNotFoundException();
        }
        return verify(published, profileId, backupId);
    }

    public List<BackupArtifact> list(String profileId) throws PersistenceException {
        validateUuid(profileId);
        Path profileDirectory = existingProfileDirectory(profileId);
        if (!Files.exists(profileDirectory, LinkOption.NOFOLLOW_LINKS)) return Collections.emptyList();
        List<BackupArtifact> items = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(profileDirectory, "*" + EXTENSION)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) continue;
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - EXTENSION.length());
                if (!UUID_V4.matcher(id).matches()) continue;
                try {
                    items.add(readManifestArtifact(file, profileId, id, "NOT_VERIFIED"));
                } catch (BackupInvalidException ex) {
                    items.add(BackupArtifact.invalid(id, Files.size(file)));
                }
            }
        } catch (IOException ex) {
            throw new PersistenceException("backup list could not be read", ex);
        }
        items.sort(Comparator.comparing(BackupStore::createdInstant,
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(BackupArtifact::getId,
                        Comparator.reverseOrder()));
        return items;
    }

    public Path resolveDownload(String profileId, String backupId) throws PersistenceException {
        validateUuid(profileId);
        validateUuid(backupId);
        Path file = publishedFile(existingProfileDirectory(profileId), backupId);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new BackupNotFoundException();
        }
        return file;
    }

    private BackupArtifact verify(Path published, String profileId, String backupId) throws PersistenceException {
        Path verifyDirectory = published.getParent().resolve(".verify-" + backupId + "-" + UUID.randomUUID()).normalize();
        Path extracted = verifyDirectory.resolve("ledger.db");
        try {
            Files.createDirectory(verifyDirectory);
            return verifyPackage(published, profileId, backupId, extracted, "VALID");
        } catch (PersistenceException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new PersistenceException("backup could not be verified", ex);
        } finally {
            deleteKnown(extracted);
            deleteKnown(verifyDirectory);
        }
    }

    private BackupArtifact verifyPackage(Path packageFile, String profileId, String backupId, Path extracted,
            String integrityStatus) throws PersistenceException {
        BackupArtifact manifestArtifact;
        try (ZipFile zip = new ZipFile(packageFile.toFile())) {
            ZipEntry manifestEntry = null;
            ZipEntry ledgerEntry = null;
            int members = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                members++;
                if (entry.isDirectory()) throw invalid("backup contains a directory");
                if ("manifest.json".equals(entry.getName()) && manifestEntry == null) manifestEntry = entry;
                else if ("ledger.db".equals(entry.getName()) && ledgerEntry == null) ledgerEntry = entry;
                else throw invalid("backup contains an unexpected or duplicate member");
            }
            if (members != 2 || manifestEntry == null || ledgerEntry == null) {
                throw invalid("backup members are incomplete");
            }
            byte[] manifestBytes = readLimited(zip.getInputStream(manifestEntry), MANIFEST_LIMIT_BYTES);
            manifestArtifact = parseManifest(manifestBytes, profileId, backupId, Files.size(packageFile), integrityStatus);
            copyLimited(zip.getInputStream(ledgerEntry), extracted, LEDGER_LIMIT_BYTES);
        } catch (PersistenceException ex) {
            throw ex;
        } catch (IOException ex) {
            throw invalid("backup container is unreadable", ex);
        }

        if (!manifestArtifact.getLedgerSha256().equals(sha256(extracted))) {
            throw invalid("backup database hash does not match");
        }
        try {
            if (Files.size(extracted) != manifestArtifact.getLedgerSizeBytes()) {
                throw invalid("backup database size does not match");
            }
            SnapshotFacts actual = inspectSnapshot(extracted);
            if (actual.schemaVersion != manifestArtifact.getSchemaVersion()
                    || actual.dataRevision != manifestArtifact.getDataRevision()
                    || !actual.profileId.equals(profileId)
                    || !actual.counts.equals(manifestArtifact.getCounts())) {
                throw invalid("backup manifest does not match its database");
            }
            return manifestArtifact;
        } catch (IOException | SQLException | PersistenceException ex) {
            throw invalid("backup database size could not be read", ex);
        }
    }

    private BackupArtifact readManifestArtifact(Path file, String profileId, String backupId, String status)
            throws PersistenceException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry("manifest.json");
            if (entry == null || entry.isDirectory()) throw invalid("backup manifest is missing");
            return parseManifest(readLimited(zip.getInputStream(entry), MANIFEST_LIMIT_BYTES), profileId, backupId,
                    Files.size(file), status);
        } catch (PersistenceException ex) {
            throw ex;
        } catch (IOException ex) {
            throw invalid("backup manifest is unreadable", ex);
        }
    }

    private BackupArtifact parseManifest(byte[] bytes, String expectedProfileId, String expectedBackupId,
            long packageSize, String integrityStatus) throws PersistenceException {
        try {
            JsonNode root = objectMapper.readTree(bytes);
            if (root == null || !root.isObject()
                    || requiredInt(root, "formatVersion") != FORMAT_VERSION
                    || !expectedBackupId.equals(requiredText(root, "backupId"))
                    || !expectedProfileId.equals(requiredText(root, "profileId"))
                    || !root.path("encrypted").isBoolean() || root.path("encrypted").booleanValue()) {
                throw invalid("backup manifest identity or format is invalid");
            }
            String createdAt = requiredText(root, "createdAt");
            Instant.parse(createdAt);
            int schemaVersion = requiredInt(root, "schemaVersion");
            if (schemaVersion < 1 || schemaVersion > LedgerBootstrap.CURRENT_SCHEMA_VERSION) {
                throw invalid("backup schema version is unsupported");
            }
            String appVersion = requiredText(root, "applicationVersion");
            long dataRevision = requiredLong(root, "dataRevision");
            JsonNode countsNode = root.path("counts");
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String name : new String[] {"records", "categories", "accounts", "metrics"}) {
                counts.put(name, requiredLong(countsNode, name));
            }
            JsonNode ledger = root.path("ledger");
            if (!"ledger.db".equals(requiredText(ledger, "fileName"))) throw invalid("backup database name is invalid");
            long ledgerSize = requiredLong(ledger, "sizeBytes");
            String hash = requiredText(ledger, "sha256");
            if (!hash.matches("[0-9a-f]{64}")) throw invalid("backup database hash is invalid");
            return new BackupArtifact(expectedBackupId, expectedProfileId, createdAt, schemaVersion, appVersion,
                    dataRevision, counts, packageSize, integrityStatus, ledgerSize, hash);
        } catch (PersistenceException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw invalid("backup manifest is invalid", ex);
        }
    }

    private byte[] manifest(String backupId, String profileId, String createdAt, SnapshotFacts facts,
            long ledgerSize, String ledgerHash) throws PersistenceException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("formatVersion", FORMAT_VERSION);
        root.put("backupId", backupId);
        root.put("schemaVersion", facts.schemaVersion);
        root.put("applicationVersion", applicationVersion);
        root.put("profileId", profileId);
        root.put("createdAt", createdAt);
        root.put("dataRevision", facts.dataRevision);
        root.put("counts", facts.counts);
        root.put("encrypted", false);
        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("fileName", "ledger.db");
        ledger.put("sizeBytes", ledgerSize);
        ledger.put("sha256", ledgerHash);
        root.put("ledger", ledger);
        try {
            return objectMapper.writeValueAsBytes(root);
        } catch (IOException ex) {
            throw new PersistenceException("backup manifest could not be written", ex);
        }
    }

    private static void onlineBackup(Path sourceLedger, Path target) throws IOException, SQLException,
            PersistenceException {
        if (!Files.isRegularFile(sourceLedger, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sourceLedger)) {
            throw new PersistenceException("source ledger is unavailable");
        }
        try (Connection connection = new SqliteDatabase(sourceLedger).open()) {
            SQLiteConnection sqlite = connection.unwrap(SQLiteConnection.class);
            int result = sqlite.getDatabase().backup("main", target.toString(), null);
            if (result != 0) throw new SQLException("SQLite online backup did not complete");
        }
    }

    private static SnapshotFacts inspectSnapshot(Path file) throws IOException, SQLException, PersistenceException {
        try (Connection connection = new SqliteDatabase(file).open()) {
            SqliteHealthCheck.full(connection);
            String profileId;
            long dataRevision;
            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("SELECT profile_id,data_revision FROM ledger_meta WHERE id=1")) {
                if (!result.next()) throw invalid("backup ledger metadata is missing");
                profileId = result.getString(1);
                dataRevision = result.getLong(2);
                if (result.next()) throw invalid("backup ledger metadata is invalid");
            }
            int schemaVersion = scalarInt(connection, "SELECT MAX(version) FROM schema_history");
            Map<String, Long> counts = new LinkedHashMap<>();
            counts.put("records", scalarLong(connection, "SELECT COUNT(*) FROM finance_record"));
            counts.put("categories", scalarLong(connection, "SELECT COUNT(*) FROM category"));
            counts.put("accounts", scalarLong(connection, "SELECT COUNT(*) FROM financial_account"));
            counts.put("metrics", scalarLong(connection, "SELECT COUNT(*) FROM metric_definition"));
            return new SnapshotFacts(profileId, schemaVersion, dataRevision, counts);
        }
    }

    private Path prepareProfileDirectory(String profileId) throws PersistenceException {
        try {
            if (Files.exists(backupsRoot, LinkOption.NOFOLLOW_LINKS) &&
                    (!Files.isDirectory(backupsRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(backupsRoot))) {
                throw new PersistenceException("backup root is unsafe");
            }
            Files.createDirectories(backupsRoot);
            Path profile = backupsRoot.resolve(profileId).normalize();
            if (!profile.getParent().equals(backupsRoot)) throw new PersistenceException("backup profile path is unsafe");
            if (Files.exists(profile, LinkOption.NOFOLLOW_LINKS) &&
                    (!Files.isDirectory(profile, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(profile))) {
                throw new PersistenceException("backup profile directory is unsafe");
            }
            Files.createDirectories(profile);
            return profile;
        } catch (IOException ex) {
            throw new PersistenceException("backup directory could not be prepared", ex);
        }
    }

    private Path existingProfileDirectory(String profileId) throws PersistenceException {
        Path profile = backupsRoot.resolve(profileId).normalize();
        if (!profile.getParent().equals(backupsRoot)) throw new PersistenceException("backup profile path is unsafe");
        if (Files.exists(backupsRoot, LinkOption.NOFOLLOW_LINKS) &&
                (!Files.isDirectory(backupsRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(backupsRoot))) {
            throw new PersistenceException("backup root is unsafe");
        }
        if (Files.exists(profile, LinkOption.NOFOLLOW_LINKS) &&
                (!Files.isDirectory(profile, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(profile))) {
            throw new PersistenceException("backup profile directory is unsafe");
        }
        return profile;
    }

    private static Path publishedFile(Path profileDirectory, String id) throws PersistenceException {
        Path file = profileDirectory.resolve(id + EXTENSION).normalize();
        if (!file.getParent().equals(profileDirectory)) throw new PersistenceException("backup path is unsafe");
        return file;
    }

    private static void writePackage(Path packageFile, byte[] manifest, Path snapshot) throws IOException {
        try (OutputStream output = Files.newOutputStream(packageFile, StandardOpenOption.CREATE_NEW);
                ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ledger.db"));
            Files.copy(snapshot, zip);
            zip.closeEntry();
        }
    }

    private static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException, PersistenceException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        try (InputStream in = input) {
            for (int read; (read = in.read(buffer)) >= 0;) {
                total += read;
                if (total > limit) throw invalid("backup manifest is too large");
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    private static void copyLimited(InputStream input, Path target, long limit)
            throws IOException, PersistenceException {
        byte[] buffer = new byte[8192];
        long total = 0;
        try (InputStream in = input; OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            for (int read; (read = in.read(buffer)) >= 0;) {
                total += read;
                if (total > limit) throw invalid("backup database is too large");
                out.write(buffer, 0, read);
            }
        }
    }

    private static String sha256(Path file) throws PersistenceException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new PersistenceException("backup hash could not be computed", ex);
        }
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException, PersistenceException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw invalid("backup count is missing");
            return result.getLong(1);
        }
    }

    private static int scalarInt(Connection connection, String sql) throws SQLException, PersistenceException {
        long value = scalarLong(connection, sql);
        if (value < 1 || value > Integer.MAX_VALUE) throw invalid("backup schema version is invalid");
        return (int) value;
    }

    private static int requiredInt(JsonNode node, String name) throws PersistenceException {
        long value = requiredLong(node, name);
        if (value > Integer.MAX_VALUE) throw invalid("backup manifest number is invalid");
        return (int) value;
    }

    private static long requiredLong(JsonNode node, String name) throws PersistenceException {
        JsonNode value = node.path(name);
        if (!value.isIntegralNumber() || value.longValue() < 0) throw invalid("backup manifest number is invalid");
        return value.longValue();
    }

    private static String requiredText(JsonNode node, String name) throws PersistenceException {
        JsonNode value = node.path(name);
        if (!value.isTextual() || value.textValue().isEmpty()) throw invalid("backup manifest text is invalid");
        return value.textValue();
    }

    private static void validateUuid(String value) throws PersistenceException {
        if (value == null || !UUID_V4.matcher(value).matches()) throw new BackupNotFoundException();
        try {
            UUID uuid = UUID.fromString(value);
            if (uuid.version() != 4 || uuid.variant() != 2 || !uuid.toString().equals(value)) {
                throw new BackupNotFoundException();
            }
        } catch (IllegalArgumentException ex) {
            throw new BackupNotFoundException();
        }
    }

    private static Instant createdInstant(BackupArtifact artifact) {
        return artifact.getCreatedAt() == null ? null : Instant.parse(artifact.getCreatedAt());
    }

    private static BackupInvalidException invalid(String message) { return new BackupInvalidException(message); }
    private static BackupInvalidException invalid(String message, Throwable cause) {
        return new BackupInvalidException(message, cause);
    }

    private static void deleteKnown(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    public static final class BackupNotFoundException extends RuntimeException {
        public BackupNotFoundException() { super("backup not found"); }
    }

    public static final class BackupInvalidException extends RuntimeException {
        public BackupInvalidException(String message) { super(message); }
        public BackupInvalidException(String message, Throwable cause) { super(message, cause); }
    }

    private static final class SnapshotFacts {
        private final String profileId;
        private final int schemaVersion;
        private final long dataRevision;
        private final Map<String, Long> counts;

        private SnapshotFacts(String profileId, int schemaVersion, long dataRevision, Map<String, Long> counts) {
            this.profileId = profileId;
            this.schemaVersion = schemaVersion;
            this.dataRevision = dataRevision;
            this.counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
        }
    }

    public static final class BackupArtifact {
        private final String id;
        private final String profileId;
        private final String createdAt;
        private final int schemaVersion;
        private final String applicationVersion;
        private final long dataRevision;
        private final Map<String, Long> counts;
        private final long packageSizeBytes;
        private final String integrityStatus;
        private final long ledgerSizeBytes;
        private final String ledgerSha256;

        private BackupArtifact(String id, String profileId, String createdAt, int schemaVersion,
                String applicationVersion, long dataRevision, Map<String, Long> counts, long packageSizeBytes,
                String integrityStatus, long ledgerSizeBytes, String ledgerSha256) {
            this.id = id;
            this.profileId = profileId;
            this.createdAt = createdAt;
            this.schemaVersion = schemaVersion;
            this.applicationVersion = applicationVersion;
            this.dataRevision = dataRevision;
            this.counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
            this.packageSizeBytes = packageSizeBytes;
            this.integrityStatus = integrityStatus;
            this.ledgerSizeBytes = ledgerSizeBytes;
            this.ledgerSha256 = ledgerSha256;
        }

        private static BackupArtifact invalid(String id, long size) {
            return new BackupArtifact(id, null, null, 0, null, 0, Collections.emptyMap(), size, "INVALID", 0, "");
        }

        private BackupArtifact withPackageSize(long size) {
            return new BackupArtifact(id, profileId, createdAt, schemaVersion, applicationVersion, dataRevision,
                    counts, size, integrityStatus, ledgerSizeBytes, ledgerSha256);
        }

        public String getId() { return id; }
        public String getProfileId() { return profileId; }
        public String getCreatedAt() { return createdAt; }
        public int getSchemaVersion() { return schemaVersion; }
        public String getApplicationVersion() { return applicationVersion; }
        public long getDataRevision() { return dataRevision; }
        public Map<String, Long> getCounts() { return counts; }
        public long getPackageSizeBytes() { return packageSizeBytes; }
        public String getIntegrityStatus() { return integrityStatus; }
        public long getLedgerSizeBytes() { return ledgerSizeBytes; }
        public String getLedgerSha256() { return ledgerSha256; }
    }
}
