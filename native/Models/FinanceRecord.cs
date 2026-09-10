using System.Text.Json.Serialization;

namespace LedgerX.Models;

public enum FinanceRecordType
{
    Income,
    FixedCost,
    VariableCost,
    FixedAssetPurchase,
    PayableCreated,
    PayablePayment,
    CustomIncrease,
    CustomDecrease
}

public enum SettlementMode
{
    PaidFromAccount,
    IncludedInOpeningBalance,
    Payable,
    NonCash
}

public enum BillingCadence { None, Daily, Weekly, Monthly, Yearly, Custom }
public enum RecognitionMethod { Immediate, StraightLineDaily, NaturalMonth, PreviousPeriod, CustomFormula }

public sealed class FinanceRecord
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public DateTime Date { get; set; } = DateTime.Today;
    public FinanceRecordType Type { get; set; }
    public decimal Amount { get; set; }
    public string Category { get; set; } = string.Empty;
    public string Account { get; set; } = "现金储备";
    public Guid? CategoryId { get; set; }
    public List<string> MetricTargetIds { get; set; } = [];
    public SettlementMode SettlementMode { get; set; } = SettlementMode.PaidFromAccount;
    public DateTime? SettlementDate { get; set; }
    public Guid? FinancialAccountId { get; set; }
    public DateTime? ServiceStart { get; set; }
    public DateTime? ServiceEndExclusive { get; set; }
    public BillingCadence BillingCadence { get; set; }
    public RecognitionMethod RecognitionMethod { get; set; } = RecognitionMethod.Immediate;
    public Guid? AllocationFormulaId { get; set; }
    public Guid? RecurringPlanId { get; set; }
    public Guid? FixedAssetId { get; set; }
    public Dictionary<string, decimal> FormulaParameterValues { get; set; } = [];
    public string Note { get; set; } = string.Empty;
    public string? CustomMetricId { get; set; }
    public string? IncomeSource { get; set; }
    public bool IsSelfGeneratedIncome { get; set; }
    public bool IsNonEssential { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.Now;
    public DateTime UpdatedAt { get; set; } = DateTime.Now;
    public DateTime? DeletedAt { get; set; }

    [JsonIgnore]
    public string TypeLabel => Type switch
    {
        FinanceRecordType.Income => "现金流入",
        FinanceRecordType.FixedCost => "固定成本",
        FinanceRecordType.VariableCost => "可变成本",
        FinanceRecordType.FixedAssetPurchase => "固定资产购置",
        FinanceRecordType.PayableCreated => "新增应付账款",
        FinanceRecordType.PayablePayment => "支付应付账款",
        FinanceRecordType.CustomIncrease => "指标增加",
        FinanceRecordType.CustomDecrease => "指标减少",
        _ => "其他"
    };

    [JsonIgnore]
    public bool IsAmountHidden { get; set; }

    [JsonIgnore]
    public string DisplayAmount => IsAmountHidden ? "••••••" : Type switch
    {
        FinanceRecordType.Income => $"+ ¥ {Amount:N2}",
        FinanceRecordType.PayableCreated => $"¥ {Amount:N2}",
        FinanceRecordType.CustomIncrease => $"+ {Amount:N2}",
        _ => $"- ¥ {Amount:N2}"
    };
}
