namespace LedgerX.Models;

public sealed class LedgerSettings
{
    public bool NotificationsEnabled { get; set; }
    public bool AutoBackupEnabled { get; set; } = true;
    public int AutoBackupIntervalDays { get; set; } = 7;
    public int AutoBackupRetentionCount { get; set; } = 10;
    public DateTime? LastAutoBackupAt { get; set; }
    public string LastSettingsSection { get; set; } = "general";
    public string CurrencySymbol { get; set; } = "¥";
    public decimal SafetyBufferTarget { get; set; } = 3000m;
}