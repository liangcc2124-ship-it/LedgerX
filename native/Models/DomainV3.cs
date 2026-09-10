namespace LedgerX.Models;

public sealed class ProfileIndex
{
    public int SchemaVersion { get; set; } = 1;
    public Guid ActiveProfileId { get; set; }
    public List<ProfileDefinition> Profiles { get; set; } = [];
}

public sealed class ProfileDefinition
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Name { get; set; } = "默认空间";
    public DateTime CreatedAt { get; set; } = DateTime.Now;
    public DateTime LastOpenedAt { get; set; } = DateTime.Now;
    public bool IsArchived { get; set; }
}

public enum FinancialAccountKind { Cash, Bank, Wallet, Credit, Loan, OtherAsset, OtherLiability }
public enum BalanceSide { Asset, Liability }

public sealed class FinancialAccount
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Name { get; set; } = string.Empty;
    public FinancialAccountKind Kind { get; set; } = FinancialAccountKind.Cash;
    public BalanceSide BalanceSide { get; set; } = BalanceSide.Asset;
    public DateTime OpeningDate { get; set; } = DateTime.Today;
    public decimal OpeningBalance { get; set; }
    public bool IncludeInAvailableCash { get; set; } = true;
    public bool IsSystem { get; set; }
    public bool IsArchived { get; set; }
}

public sealed class CategoryDefinition
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Name { get; set; } = string.Empty;
    public Guid? ParentId { get; set; }
    public List<FinanceRecordType> ApplicableRecordTypes { get; set; } = [];
    public bool IsSystem { get; set; }
    public bool IsArchived { get; set; }
    public int SortOrder { get; set; }
    public RecognitionMethod DefaultRecognitionMethod { get; set; } = RecognitionMethod.Immediate;
    public string? RecommendedDepreciationTemplateId { get; set; }
}

public enum MetricPeriodBehavior { Period, PointInTime, Rolling }
public enum FormulaScope { Metric, Allocation, Depreciation }
public enum FormulaResultType { Money, Number, Percent }
public enum FormulaNodeType { Constant, Variable, Add, Subtract, Multiply, Divide, Negate, Min, Max, Round, Abs, Clamp, Average }

public enum FormulaTokenKind { Source, Constant, Operator, Function }

public sealed class FormulaToken
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public FormulaTokenKind Kind { get; set; }
    public string Value { get; set; } = string.Empty;
    public string? Label { get; set; }
}

public sealed class FormulaExpression
{
    public FormulaNodeType NodeType { get; set; }
    public decimal? Constant { get; set; }
    public string? Variable { get; set; }
    public List<FormulaExpression> Arguments { get; set; } = [];

    public static FormulaExpression Value(decimal value) => new() { NodeType = FormulaNodeType.Constant, Constant = value };
    public static FormulaExpression Ref(string name) => new() { NodeType = FormulaNodeType.Variable, Variable = name };
    public static FormulaExpression Op(FormulaNodeType type, params FormulaExpression[] args) => new() { NodeType = type, Arguments = [.. args] };
}

public sealed class FormulaDefinition
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public FormulaScope Scope { get; set; }
    public int Version { get; set; } = 1;
    public FormulaExpression RootExpression { get; set; } = FormulaExpression.Value(0);
    public FormulaResultType ResultType { get; set; } = FormulaResultType.Money;
    public List<FormulaToken> Tokens { get; set; } = [];
    public List<string> Dependencies { get; set; } = [];
    public DateTime CreatedAt { get; set; } = DateTime.Now;
    public DateTime EffectiveFrom { get; set; } = DateTime.Today;
    public bool IsTemplate { get; set; }
}

public sealed class MetricDefinition
{
    public string Id { get; set; } = $"custom-{Guid.NewGuid():N}";
    public string Name { get; set; } = string.Empty;
    public string Description { get; set; } = string.Empty;
    public MetricDisplayFormat DisplayFormat { get; set; } = MetricDisplayFormat.Currency;
    public int Precision { get; set; } = 2;
    public Guid FormulaId { get; set; }
    public string? TemplateId { get; set; }
    public int TemplateVersion { get; set; } = 1;
    public MetricPeriodBehavior PeriodBehavior { get; set; } = MetricPeriodBehavior.Period;
    public bool AcceptsDirectRecordAssignment { get; set; }
    public bool IsSystem { get; set; }
    public bool IsEnabled { get; set; } = true;
    public bool IsArchived { get; set; }
}

public sealed record MetricTemplate(
    string Id,
    string Name,
    string Description,
    MetricDisplayFormat DisplayFormat,
    MetricPeriodBehavior PeriodBehavior,
    FormulaExpression Formula,
    bool AcceptsDirectRecordAssignment = false);

public sealed class AllocationPlan
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid RecordId { get; set; }
    public decimal Amount { get; set; }
    public DateTime ServiceStart { get; set; }
    public DateTime ServiceEndExclusive { get; set; }
    public RecognitionMethod RecognitionMethod { get; set; } = RecognitionMethod.StraightLineDaily;
    public Guid? FormulaId { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.Now;
    public DateTime UpdatedAt { get; set; } = DateTime.Now;
}

public sealed record AllocationSlice(DateTime Start, DateTime EndExclusive, decimal Amount);

public sealed class RecurringPlan
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Name { get; set; } = string.Empty;
    public BillingCadence Cadence { get; set; } = BillingCadence.Monthly;
    public DateTime NextDate { get; set; } = DateTime.Today;
    public decimal Amount { get; set; }
    public bool IsPaused { get; set; }
    public DateTime? EndedAt { get; set; }
}

public enum DepreciationMethod { StraightLine, UnitsOfProduction, DoubleDecliningBalance, SumOfYearsDigits, CustomFormula }
public enum FixedAssetStatus { Draft, Active, Disposed, Archived }

public sealed class FixedAsset
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Name { get; set; } = string.Empty;
    public Guid? CategoryId { get; set; }
    public Guid? AcquisitionRecordId { get; set; }
    public DateTime InServiceDate { get; set; } = DateTime.Today;
    public decimal Cost { get; set; }
    public decimal SalvageValue { get; set; }
    public int UsefulLifeMonths { get; set; }
    public decimal TotalProductionUnits { get; set; }
    public decimal UsedProductionUnits { get; set; }
    public DepreciationMethod Method { get; set; } = DepreciationMethod.StraightLine;
    public Guid? FormulaId { get; set; }
    public decimal AccumulatedDepreciation { get; set; }
    public FixedAssetStatus Status { get; set; } = FixedAssetStatus.Draft;
    public DateTime? DisposedAt { get; set; }
    public decimal? DisposalProceeds { get; set; }
}

public sealed record DepreciationResult(decimal PeriodDepreciation, decimal AccumulatedDepreciation, decimal BookValue, string Method, IReadOnlyList<AllocationSlice> Schedule);

public sealed class DashboardLayout
{
    public Guid ProfileId { get; set; }
    public string Breakpoint { get; set; } = "desktop";
    public int Version { get; set; } = 1;
    public List<DashboardLayoutItem> Items { get; set; } = [];
    public DateTime UpdatedAt { get; set; } = DateTime.Now;
}

public sealed class DashboardLayoutItem
{
    public string WidgetId { get; set; } = string.Empty;
    public int X { get; set; }
    public int Y { get; set; }
    public int W { get; set; } = 3;
    public int H { get; set; } = 2;
    public int MinW { get; set; } = 2;
    public int MinH { get; set; } = 1;
    public int MaxW { get; set; } = 12;
    public int MaxH { get; set; } = 8;
}

public sealed class MigrationIssue
{
    public string Code { get; set; } = string.Empty;
    public string EntityId { get; set; } = string.Empty;
    public string Message { get; set; } = string.Empty;
}

public sealed record FormulaValidationResult(bool IsValid, IReadOnlyList<string> Errors, IReadOnlyList<string> Dependencies);

public sealed class BridgeException : Exception
{
    public string Code { get; }
    public IReadOnlyDictionary<string, string> FieldErrors { get; }
    public string? RecoverySuggestion { get; }

    public BridgeException(string code, string message, IReadOnlyDictionary<string, string>? fieldErrors = null, string? recoverySuggestion = null)
        : base(message)
    {
        Code = code;
        FieldErrors = fieldErrors ?? new Dictionary<string, string>();
        RecoverySuggestion = recoverySuggestion;
    }
}
