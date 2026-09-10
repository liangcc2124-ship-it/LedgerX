using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using LedgerX.Models;

namespace LedgerX.Services;

public sealed class LedgerStore
{
    public const int CurrentSchemaVersion = 2;
    private readonly JsonSerializerOptions _jsonOptions = new() { WriteIndented = true, PropertyNamingPolicy = JsonNamingPolicy.CamelCase, PropertyNameCaseInsensitive = true };
    private readonly JsonSerializerOptions _compactOptions = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, PropertyNameCaseInsensitive = true };
    public string DataDirectory { get; } = Environment.GetEnvironmentVariable("LEDGERX_DATA_DIR")
        ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LedgerX");
    public string DataFile => Path.Combine(DataDirectory, "ledger.json");
    public string SnapshotDirectory => Path.Combine(DataDirectory, "Snapshots");
    public string AutoBackupDirectory => Path.Combine(DataDirectory, "AutoBackups");
    public bool RecoveryRequired { get; private set; }
    public string? RecoveryMessage { get; private set; }
    public string? DamagedFile { get; private set; }
    public string? LastBackupPath { get; private set; }
    public string? LastProtectionSnapshot { get; private set; }

    public LedgerState Load()
    {
        Directory.CreateDirectory(DataDirectory);
        if (!File.Exists(DataFile)) return NewState();
        try
        {
            var state = JsonSerializer.Deserialize<LedgerState>(File.ReadAllText(DataFile), _jsonOptions)
                ?? throw new InvalidDataException("账本内容为空。");
            if (state.SchemaVersion < CurrentSchemaVersion) CreateProtectionSnapshot("before-migration", state);
            return Migrate(state);
        }
        catch (Exception ex) when (ex is JsonException or InvalidDataException)
        {
            DamagedFile = PreserveDamagedFile(); RecoveryRequired = true; RecoveryMessage = ex.Message;
            return NewState();
        }
    }

    public void Save(LedgerState state)
    {
        Directory.CreateDirectory(DataDirectory);
        state.SchemaVersion = CurrentSchemaVersion;
        state.UpdatedAt = DateTime.Now;
        WriteAtomically(DataFile, JsonSerializer.Serialize(state, _jsonOptions));
    }

    public void Backup(string targetPath, LedgerState state)
    {
        var envelope = CreateEnvelope(state);
        var json = JsonSerializer.Serialize(envelope, _jsonOptions);
        WriteAtomically(targetPath, json);
        var preview = PreviewBackup(targetPath);
        if (!preview.IsChecksumValid) throw new InvalidDataException("备份写入后校验失败。");
        state.LastBackupAt = envelope.CreatedAt;
        LastBackupPath = Path.GetFullPath(targetPath);
        Save(state);
    }

    public BackupPreview PreviewBackup(string sourcePath)
    {
        var json = File.ReadAllText(sourcePath);
        using var document = JsonDocument.Parse(json);
        if (HasFormatVersion(document.RootElement))
        {
            var envelope = JsonSerializer.Deserialize<BackupEnvelope>(json, _jsonOptions)
                ?? throw new InvalidDataException("备份信封内容无效。");
            if (envelope.FormatVersion != 1) throw new InvalidDataException("不支持该备份格式版本。");
            var valid = FixedTimeEquals(envelope.Checksum, Checksum(envelope.Payload));
            return new BackupPreview(Path.GetFileName(sourcePath), envelope.CreatedAt, envelope.AppVersion,
                envelope.Summary.RecordCount, envelope.Summary.DeletedRecordCount, envelope.Summary.CustomMetricCount,
                envelope.Summary.WarningRuleCount, envelope.Summary.FirstRecordDate, envelope.Summary.LastRecordDate,
                valid, false);
        }

        var legacy = JsonSerializer.Deserialize<LedgerState>(json, _jsonOptions)
            ?? throw new InvalidDataException("旧版备份内容无效。");
        var summary = BuildSummary(legacy);
        return new BackupPreview(Path.GetFileName(sourcePath), File.GetCreationTime(sourcePath), "2.0.x",
            summary.RecordCount, summary.DeletedRecordCount, summary.CustomMetricCount, summary.WarningRuleCount,
            summary.FirstRecordDate, summary.LastRecordDate, true, true);
    }

    public LedgerState Restore(string sourcePath)
    {
        try { return RestoreCore(sourcePath); }
        catch (JsonException ex) { throw new InvalidDataException("备份格式无效。", ex); }
    }

    private LedgerState RestoreCore(string sourcePath)
    {
        var json = File.ReadAllText(sourcePath);
        using var document = JsonDocument.Parse(json);
        LedgerState state;
        if (HasFormatVersion(document.RootElement))
        {
            var envelope = JsonSerializer.Deserialize<BackupEnvelope>(json, _jsonOptions)
                ?? throw new InvalidDataException("备份内容无效。");
            if (!FixedTimeEquals(envelope.Checksum, Checksum(envelope.Payload)))
                throw new InvalidDataException("备份校验失败，文件可能已损坏或被修改。");
            state = envelope.Payload;
        }
        else
        {
            state = JsonSerializer.Deserialize<LedgerState>(json, _jsonOptions)
                ?? throw new InvalidDataException("旧版备份内容无效。");
        }
        if (state.SchemaVersion > CurrentSchemaVersion)
            throw new InvalidDataException("该备份来自更高版本的 LedgerX，请先升级应用。");
        return Migrate(state);
    }

    public string CreateProtectionSnapshot(string reason, LedgerState state)
    {
        Directory.CreateDirectory(SnapshotDirectory);
        var safeReason = string.Concat(reason.Where(ch => char.IsLetterOrDigit(ch) || ch == '-'));
        var path = Path.Combine(SnapshotDirectory, $"{DateTime.Now:yyyyMMdd-HHmmss}-{safeReason}.json");
        WriteAtomically(path, JsonSerializer.Serialize(CreateEnvelope(state), _jsonOptions));
        LastProtectionSnapshot = path;
        return path;
    }

    public LedgerState RestoreProtectionSnapshot(string path) => Restore(path);
    public IReadOnlyList<string> GetAvailableSnapshots() => Directory.Exists(SnapshotDirectory) ? Directory.GetFiles(SnapshotDirectory, "*.json").OrderByDescending(File.GetLastWriteTime).Take(20).ToList() : [];
    public void CompleteRecovery() { RecoveryRequired = false; RecoveryMessage = null; }
    public void CompleteRecoveryWithEmptyLedger() { RecoveryRequired = false; RecoveryMessage = null; Save(NewState()); }
    public string? TryAutoBackup(LedgerState state)
    {
        if (!state.Settings.AutoBackupEnabled) return null;
        if (state.Settings.LastAutoBackupAt is not null && DateTime.Now - state.Settings.LastAutoBackupAt < TimeSpan.FromDays(Math.Max(1, state.Settings.AutoBackupIntervalDays))) return null;
        Directory.CreateDirectory(AutoBackupDirectory);
        var path = Path.Combine(AutoBackupDirectory, $"LedgerX-auto-{DateTime.Now:yyyyMMdd-HHmmss}.json");
        WriteAtomically(path, JsonSerializer.Serialize(CreateEnvelope(state), _jsonOptions));
        state.Settings.LastAutoBackupAt = DateTime.Now;
        var keep = Math.Max(1, state.Settings.AutoBackupRetentionCount);
        foreach (var old in Directory.GetFiles(AutoBackupDirectory, "LedgerX-auto-*.json").OrderByDescending(File.GetLastWriteTime).Skip(keep)) File.Delete(old);
        return path;
    }
    private BackupEnvelope CreateEnvelope(LedgerState state)
    {
        var envelope = new BackupEnvelope
        {
            CreatedAt = DateTime.Now,
            AppVersion = "2.1.0",
            Summary = BuildSummary(state),
            Payload = state
        };
        envelope.Checksum = Checksum(envelope.Payload);
        return envelope;
    }

    private static BackupSummary BuildSummary(LedgerState state)
    {
        var dates = state.Records.Select(x => x.Date).ToList();
        return new BackupSummary
        {
            RecordCount = state.Records.Count(x => x.DeletedAt is null),
            DeletedRecordCount = state.Records.Count(x => x.DeletedAt is not null),
            CustomMetricCount = state.CustomMetrics.Count,
            WarningRuleCount = state.WarningRules.Count,
            FirstRecordDate = dates.Count == 0 ? null : dates.Min(),
            LastRecordDate = dates.Count == 0 ? null : dates.Max()
        };
    }

    private LedgerState Migrate(LedgerState state)
    {
        if (state.SchemaVersion > CurrentSchemaVersion)
            throw new InvalidDataException("账本版本高于当前应用版本，已拒绝加载。");
        if (state.CreatedAt == default) state.CreatedAt = DateTime.Now;
        state.Records ??= []; state.CustomMetrics ??= []; state.HiddenMetrics ??= []; state.EnabledMetrics ??= []; state.WarningRules ??= []; state.WarningEvents ??= []; state.Settings ??= new LedgerSettings();
        foreach (var rule in state.WarningRules) rule.Conditions ??= [];
        foreach (var record in state.Records)
        {
            if (record.CreatedAt == default) record.CreatedAt = record.Date;
            if (record.UpdatedAt == default) record.UpdatedAt = record.CreatedAt;
            if (record.Type == FinanceRecordType.Income && string.IsNullOrWhiteSpace(record.IncomeSource))
            {
                record.IncomeSource = record.Category.Contains("父母") ? "父母支持" : "未知";
                record.IsSelfGeneratedIncome = !record.Category.Contains("父母");
            }
        }
        state.SchemaVersion = CurrentSchemaVersion;
        return state;
    }

    private LedgerState NewState() => new() { SchemaVersion = CurrentSchemaVersion };

    private string Checksum(LedgerState state)
    {
        var payload = JsonSerializer.Serialize(state, _compactOptions);
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(payload))).ToLowerInvariant();
    }

    private static bool HasFormatVersion(JsonElement root) => root.TryGetProperty("formatVersion", out _) || root.TryGetProperty("FormatVersion", out _);

    private static bool FixedTimeEquals(string expected, string actual)
    {
        if (expected.Length != actual.Length) return false;
        return CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(expected), Encoding.UTF8.GetBytes(actual));
    }

    private static void WriteAtomically(string targetPath, string content)
    {
        var fullPath = Path.GetFullPath(targetPath);
        Directory.CreateDirectory(Path.GetDirectoryName(fullPath)!);
        var temporary = fullPath + ".tmp";
        File.WriteAllText(temporary, content, new UTF8Encoding(false));
        File.Move(temporary, fullPath, true);
    }

    private string PreserveDamagedFile()
    {
        var damaged = Path.Combine(DataDirectory, $"ledger.damaged-{DateTime.Now:yyyyMMdd-HHmmss}.json");
        File.Copy(DataFile, damaged, true);
        return damaged;
    }
}
