using LedgerX.Models;
using LedgerX.Services;

namespace LedgerX.Tests;

public class MetricEngineTests
{
    private static FinanceRecord Record(FinanceRecordType type, decimal amount, DateTime date, string? source = null, bool own = false) => new() { Type = type, Amount = amount, Date = date, IncomeSource = source, IsSelfGeneratedIncome = own };

    [Fact]
    public void Income_and_costs_update_cash_assets_and_payables()
    {
        var records = new[] { Record(FinanceRecordType.Income, 1000, new DateTime(2026, 9, 1)), Record(FinanceRecordType.FixedCost, 200, new DateTime(2026, 9, 2)), Record(FinanceRecordType.FixedAssetPurchase, 300, new DateTime(2026, 9, 3)), Record(FinanceRecordType.PayableCreated, 150, new DateTime(2026, 9, 4)) };
        var values = new MetricEngine().Calculate(records, MetricEngine.Range("month", new DateTime(2026, 9, 10)));
        Assert.Equal(500m, values["netflow"].Value);
        Assert.Equal(500m, values["cash"].Value);
        Assert.Equal(300m, values["fixedassets"].Value);
        Assert.Equal(150m, values["payables"].Value);
        Assert.Equal(800m, values["assets"].Value);
        Assert.Equal(650m, values["netassets"].Value);
    }

    [Fact]
    public void Burn_requires_three_complete_months_and_runway_uses_cash()
    {
        var records = new List<FinanceRecord> { Record(FinanceRecordType.Income, 3000, new DateTime(2026, 9, 1)) };
        records.AddRange(new[] { 500m, 600m, 700m }.Select((amount, index) => Record(FinanceRecordType.VariableCost, amount, new DateTime(2026, 6 + index, 15))));
        var values = new MetricEngine().Calculate(records, MetricEngine.Range("month", new DateTime(2026, 9, 10)));
        Assert.Equal(600m, values["burn"].Value);
        Assert.Equal(2m, values["runway"].Value);
        var insufficient = new MetricEngine().Calculate(records.Take(2), MetricEngine.Range("month", new DateTime(2026, 9, 10)));
        Assert.Equal("insufficient", insufficient["burn"].Status);
    }

    [Fact]
    public void Zero_denominator_is_not_reported_as_zero_percent()
    {
        var values = new MetricEngine().Calculate([], MetricEngine.Range("month", new DateTime(2026, 9, 10)));
        Assert.Null(values["dependency"].Value);
        Assert.Equal("not-computable", values["dependency"].Status);
    }
}

public class WarningEngineTests
{
    [Fact]
    public void Threshold_and_consecutive_conditions_are_explainable()
    {
        var state = new LedgerState();
        state.Records.Add(new FinanceRecord { Type = FinanceRecordType.Income, Amount = 1000, Date = new DateTime(2026, 9, 1) });
        state.Records.Add(new FinanceRecord { Type = FinanceRecordType.FixedCost, Amount = 1200, Date = new DateTime(2026, 9, 2) });
        var rule = new WarningRule { MetricId = "cash", Operator = "<", Threshold = 0, Period = "month", ConsecutivePeriods = 1 };
        state.WarningRules.Add(rule);
        var result = new WarningEngine(new MetricEngine()).Preview(rule, state, new DateTime(2026, 9, 10));
        Assert.True(result.CanEvaluate);
        Assert.True(result.Triggered);
        Assert.Contains("cash", result.Explanation);
        new WarningEngine(new MetricEngine()).EvaluateAll(state, new DateTime(2026, 9, 10));
        Assert.Single(state.WarningEvents);
        Assert.Equal("active", state.WarningEvents[0].Status);
    }

    [Fact]
    public void Resolved_rule_does_not_remain_active_after_data_changes()
    {
        var state = new LedgerState();
        state.Records.Add(new FinanceRecord { Type = FinanceRecordType.Income, Amount = 1000, Date = new DateTime(2026, 9, 1) });
        var rule = new WarningRule { MetricId = "cash", Operator = "<", Threshold = 0, Period = "month" };
        state.WarningRules.Add(rule); var engine = new WarningEngine(new MetricEngine()); engine.EvaluateAll(state, new DateTime(2026, 9, 10));
        Assert.Empty(state.WarningEvents);
        state.Records.Add(new FinanceRecord { Type = FinanceRecordType.FixedCost, Amount = 1200, Date = new DateTime(2026, 9, 2) }); engine.EvaluateAll(state, new DateTime(2026, 9, 10));
        Assert.Single(state.WarningEvents); state.Records.RemoveAt(1); engine.EvaluateAll(state, new DateTime(2026, 9, 11)); Assert.Equal("resolved", state.WarningEvents[0].Status);
    }
}

public class ReportEngineTests
{
    [Fact]
    public void Reports_group_periods_and_keep_record_drilldown_ids()
    {
        var state = new LedgerState();
        var first = new FinanceRecord { Type = FinanceRecordType.Income, Amount = 1000, Date = new DateTime(2026, 9, 1), IncomeSource = "兼职", IsSelfGeneratedIncome = true, Category = "工资" };
        var second = new FinanceRecord { Type = FinanceRecordType.VariableCost, Amount = 250, Date = new DateTime(2026, 9, 2), Category = "吃饭" };
        state.Records.AddRange([first, second]);
        var engine = new ReportEngine(new MetricEngine());
        var month = engine.Build(state, "month", new DateTime(2026, 9, 10));
        Assert.Equal("ready", month.DataStatus);
        Assert.Equal(750m, month.Metrics["netflow"]);
        Assert.Contains(first.Id, month.RecordIds);
        Assert.Contains(second.Id, month.RecordIds);
        Assert.Contains(month.CostCategories, x => x.Label == "吃饭" && x.Value == 250m);
        Assert.NotNull(month.Series);
    }
}
public class LedgerStoreTests
{
    [Fact]
    public void Backup_preview_restore_and_checksum_reject_tampering()
    {
        var directory = Path.Combine(Path.GetTempPath(), "LedgerX-tests-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(directory);
        try
        {
            Environment.SetEnvironmentVariable("LEDGERX_DATA_DIR", directory);
            var store = new LedgerStore(); var state = new LedgerState(); state.Records.Add(new FinanceRecord { Type = FinanceRecordType.Income, Amount = 42, Date = DateTime.Today }); store.Save(state);
            var backup = Path.Combine(directory, "backup.json"); store.Backup(backup, state); var preview = store.PreviewBackup(backup); Assert.True(preview.IsChecksumValid); Assert.Equal(1, preview.RecordCount); Assert.Single(store.Restore(backup).Records);
            File.AppendAllText(backup, "x"); Assert.Throws<InvalidDataException>(() => store.Restore(backup));
        }
        finally { Environment.SetEnvironmentVariable("LEDGERX_DATA_DIR", null); try { Directory.Delete(directory, true); } catch { } }
    }
}