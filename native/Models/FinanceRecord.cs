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

public sealed class FinanceRecord
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public DateTime Date { get; set; } = DateTime.Today;
    public FinanceRecordType Type { get; set; }
    public decimal Amount { get; set; }
    public string Category { get; set; } = string.Empty;
    public string Account { get; set; } = "现金储备";
    public string Note { get; set; } = string.Empty;
    public string? CustomMetricId { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.Now;

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
