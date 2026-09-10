namespace LedgerX.Models;

public sealed class BackupEnvelope
{
    public int FormatVersion { get; set; } = 2;
    public string AppVersion { get; set; } = "3.0.0";
    public Guid ProfileId { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.Now;
    public BackupSummary Summary { get; set; } = new();
    public string Checksum { get; set; } = string.Empty;
    public LedgerState Payload { get; set; } = new();
}

public sealed class BackupSummary
{
    public int RecordCount { get; set; }
    public int DeletedRecordCount { get; set; }
    public int CustomMetricCount { get; set; }
    public int WarningRuleCount { get; set; }
    public DateTime? FirstRecordDate { get; set; }
    public DateTime? LastRecordDate { get; set; }
}

public sealed record BackupPreview(
    string FileName,
    DateTime CreatedAt,
    string AppVersion,
    int RecordCount,
    int DeletedRecordCount,
    int CustomMetricCount,
    int WarningRuleCount,
    DateTime? FirstRecordDate,
    DateTime? LastRecordDate,
    bool IsChecksumValid,
    bool IsLegacy);
