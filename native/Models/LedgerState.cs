namespace LedgerX.Models;

public sealed class LedgerState
{
    public int SchemaVersion { get; set; } = 3;
    public Guid ProfileId { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.Now;
    public DateTime UpdatedAt { get; set; } = DateTime.Now;
    public DateTime? LastBackupAt { get; set; }
    public List<FinanceRecord> Records { get; set; } = [];
    public HashSet<string> HiddenMetrics { get; set; } = [];
    public HashSet<string> EnabledMetrics { get; set; } = [];
    public List<CustomMetricDefinition> CustomMetrics { get; set; } = [];
    public List<CategoryDefinition> Categories { get; set; } = [];
    public List<FinancialAccount> FinancialAccounts { get; set; } = [];
    public List<MetricDefinition> MetricDefinitions { get; set; } = [];
    public List<FormulaDefinition> FormulaDefinitions { get; set; } = [];
    public List<AllocationPlan> AllocationPlans { get; set; } = [];
    public List<RecurringPlan> RecurringPlans { get; set; } = [];
    public List<FixedAsset> FixedAssets { get; set; } = [];
    public List<DashboardLayout> DashboardLayouts { get; set; } = [];
    public List<MigrationIssue> MigrationIssues { get; set; } = [];
    public List<WarningRule> WarningRules { get; set; } = [];
    public List<WarningEvent> WarningEvents { get; set; } = [];
    public LedgerSettings Settings { get; set; } = new();
    public bool HideAllAmounts { get; set; }
    public string ThemeName { get; set; } = "暖铜";
    public string? CustomThemePath { get; set; }
    public string? CustomThemeCss { get; set; }
}
