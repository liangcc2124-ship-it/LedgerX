namespace LedgerX.Models;

public sealed class WarningCondition
{
    public string MetricId { get; set; } = "cash";
    public string Operator { get; set; } = "<";
    public decimal Threshold { get; set; }
    public string ComparisonMode { get; set; } = "threshold";
    public int ComparisonWindow { get; set; } = 4;
}

public sealed class WarningRule
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Name { get; set; } = "现金储备提醒";
    public string Description { get; set; } = string.Empty;
    public bool Enabled { get; set; } = true;
    public string MetricId { get; set; } = "cash";
    public string Operator { get; set; } = "<";
    public decimal Threshold { get; set; }
    public string Unit { get; set; } = "元";
    public string Period { get; set; } = "month";
    public string ComparisonMode { get; set; } = "threshold";
    public int ComparisonWindow { get; set; } = 4;
    public int ConsecutivePeriods { get; set; } = 1;
    public string Severity { get; set; } = "关注";
    public string RepeatPolicy { get; set; } = "cooldown";
    public int CooldownHours { get; set; } = 24;
    public DateTime? EffectiveFrom { get; set; }
    public DateTime? EffectiveTo { get; set; }
    public string NotificationMethod { get; set; } = "inApp";
    public bool ShowAmountInNotification { get; set; }
    public List<WarningCondition> Conditions { get; set; } = [];
    public DateTime CreatedAt { get; set; } = DateTime.Now;
    public DateTime UpdatedAt { get; set; } = DateTime.Now;
}

public sealed class WarningEvent
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid RuleId { get; set; }
    public string Status { get; set; } = "active";
    public decimal? MeasuredValue { get; set; }
    public decimal Threshold { get; set; }
    public DateTime PeriodStart { get; set; }
    public DateTime PeriodEnd { get; set; }
    public DateTime TriggeredAt { get; set; } = DateTime.Now;
    public DateTime LastEvaluatedAt { get; set; } = DateTime.Now;
    public DateTime? ResolvedAt { get; set; }
    public DateTime? SnoozedUntil { get; set; }
    public List<Guid> EvidenceRecordIds { get; set; } = [];
    public string Explanation { get; set; } = string.Empty;
}