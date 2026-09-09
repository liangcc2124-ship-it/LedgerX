namespace LedgerX.Models;

public sealed class LedgerState
{
    public List<FinanceRecord> Records { get; set; } = [];
    public HashSet<string> HiddenMetrics { get; set; } = [];
    public HashSet<string> EnabledMetrics { get; set; } = [];
    public List<CustomMetricDefinition> CustomMetrics { get; set; } = [];
    public bool HideAllAmounts { get; set; }
    public string ThemeName { get; set; } = "暖铜";
    public string? CustomThemePath { get; set; }
    public string? CustomThemeCss { get; set; }
}
