using LedgerX.Models;
using LedgerX.Services;

namespace LedgerX.Tests;
public class SingleInstanceCoordinatorTests
{
    [Fact]
    public void Names_are_stable_per_user_scope_and_do_not_expose_the_scope()
    {
        const string scope = "S-1-5-21-12345";

        Assert.Equal(SingleInstanceIdentity.MutexName(scope), SingleInstanceIdentity.MutexName(scope));
        Assert.Equal(SingleInstanceIdentity.PipeName(scope), SingleInstanceIdentity.PipeName(scope));
        Assert.DoesNotContain(scope, SingleInstanceIdentity.MutexName(scope));
        Assert.DoesNotContain(scope, SingleInstanceIdentity.PipeName(scope));
        Assert.NotEqual(SingleInstanceIdentity.MutexName(scope), SingleInstanceIdentity.MutexName("S-1-5-21-67890"));
    }

    [Fact]
    public async Task Secondary_instance_notifies_primary_over_its_user_pipe()
    {
        var activated = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var scope = "test-" + Guid.NewGuid().ToString("N");

        var notified = await Task.Run(() =>
        {
            using var primary = new SingleInstanceCoordinator(scope, () => activated.TrySetResult());
            if (!primary.TryAcquirePrimary())
            {
                return false;
            }

            primary.StartListening();
            using var secondary = new SingleInstanceCoordinator(scope, () => { });
            return !secondary.TryAcquirePrimary() && secondary.TryActivateExisting();
        }).WaitAsync(TimeSpan.FromSeconds(5));

        Assert.True(notified);
        await activated.Task.WaitAsync(TimeSpan.FromSeconds(3));
    }

    [Fact]
    public void Shutdown_releases_the_mutex_for_a_new_primary_instance()
    {
        var scope = "test-" + Guid.NewGuid().ToString("N");
        var first = new SingleInstanceCoordinator(scope, () => { });
        Assert.True(first.TryAcquirePrimary());
        first.Dispose();

        using var replacement = new SingleInstanceCoordinator(scope, () => { });
        Assert.True(replacement.TryAcquirePrimary());
    }
}
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
public class V3AccountingTests
{
    [Fact]
    public void Historical_record_in_opening_balance_does_not_reduce_available_cash()
    {
        var account = new FinancialAccount { Name = "现金", OpeningBalance = 1000m, OpeningDate = new DateTime(2026, 1, 1) };
        var state = new LedgerState { FinancialAccounts = [account] };
        state.Records.Add(new FinanceRecord
        {
            Type = FinanceRecordType.FixedCost, Amount = 250m, Date = new DateTime(2025, 12, 15),
            SettlementMode = SettlementMode.IncludedInOpeningBalance, FinancialAccountId = account.Id
        });

        Assert.Equal(1000m, new AccountEngine().AvailableCash(state, new DateTime(2026, 9, 10)));
    }

    [Fact]
    public void Subscription_starting_mid_month_is_recognized_by_actual_service_days()
    {
        var plan = new AllocationPlan
        {
            Amount = 300m, ServiceStart = new DateTime(2026, 9, 10), ServiceEndExclusive = new DateTime(2026, 10, 10),
            RecognitionMethod = RecognitionMethod.StraightLineDaily
        };

        var amount = new AllocationEngine().RecognizedAmount(plan, new DateTime(2026, 9, 1), new DateTime(2026, 9, 30));
        Assert.Equal(210m, amount);
    }
}
public class StructuredFormulaTests
{
    [Fact]
    public void Tokens_respect_operator_precedence_and_parentheses()
    {
        FormulaToken Token(string value, FormulaTokenKind kind) => new() { Value = value, Kind = kind };
        var engine = new FormulaEngine();
        var expression = engine.ParseTokens([
            Token("记录金额合计", FormulaTokenKind.Source), Token("+", FormulaTokenKind.Operator), Token("2", FormulaTokenKind.Constant), Token("×", FormulaTokenKind.Operator), Token("3", FormulaTokenKind.Constant)
        ], FormulaScope.Allocation);
        Assert.Equal(10m, engine.Evaluate(expression, new Dictionary<string, decimal> { ["totalAmount"] = 4m }));
        var grouped = engine.ParseTokens([
            Token("(", FormulaTokenKind.Operator), Token("记录金额合计", FormulaTokenKind.Source), Token("+", FormulaTokenKind.Operator), Token("2", FormulaTokenKind.Constant), Token(")", FormulaTokenKind.Operator), Token("×", FormulaTokenKind.Operator), Token("3", FormulaTokenKind.Constant)
        ], FormulaScope.Allocation);
        Assert.Equal(18m, engine.Evaluate(grouped, new Dictionary<string, decimal> { ["totalAmount"] = 4m }));
    }

    [Fact]
    public void Unknown_formula_source_is_rejected()
    {
        var token = new FormulaToken { Value = "任意脚本", Kind = FormulaTokenKind.Source };
        var error = Assert.Throws<BridgeException>(() => new FormulaEngine().ParseTokens([token], FormulaScope.Metric));
        Assert.Equal("formula_source_invalid", error.Code);
    }

    [Fact]
    public void Custom_metric_definition_uses_saved_formula()
    {
        var formula = new FormulaDefinition
        {
            Scope = FormulaScope.Metric,
            RootExpression = FormulaExpression.Op(FormulaNodeType.Subtract, FormulaExpression.Ref("metric:inflow"), FormulaExpression.Ref("metric:outflow"))
        };
        var definition = new MetricDefinition { Id = "custom-margin", Name = "自定义净额", FormulaId = formula.Id, IsEnabled = true };
        var records = new[]
        {
            new FinanceRecord { Type = FinanceRecordType.Income, Amount = 500m, Date = new DateTime(2026, 9, 1) },
            new FinanceRecord { Type = FinanceRecordType.VariableCost, Amount = 120m, Date = new DateTime(2026, 9, 2) }
        };
        var values = new MetricEngine().Calculate(records, MetricEngine.Range("month", new DateTime(2026, 9, 10)), metricDefinitions: [definition], formulaDefinitions: [formula]);
        Assert.Equal(380m, values[definition.Id].Value);
    }
    [Fact]
    public void Depreciation_schedule_preserves_residual_value()
    {
        var asset = new FixedAsset { Name = "电脑", Cost = 1200m, SalvageValue = 120m, UsefulLifeMonths = 12, InServiceDate = new DateTime(2026, 1, 1), Method = DepreciationMethod.StraightLine, Status = FixedAssetStatus.Active };
        var result = new AllocationEngine().BuildDepreciationSchedule(asset, new DateTime(2026, 12, 31));
        Assert.Equal(1080m, result.AccumulatedDepreciation);
        Assert.Equal(120m, result.BookValue);
        Assert.Equal(12, result.Schedule.Count);
    }
}
public class ProfileStoreTests
{
    [Fact]
    public void Profile_spaces_have_distinct_data_directories()
    {
        var directory = Path.Combine(Path.GetTempPath(), "LedgerX-profile-tests-" + Guid.NewGuid().ToString("N"));
        try
        {
            var store = new ProfileStore(directory); var index = store.Load();
            var second = new ProfileDefinition { Name = "第二空间" }; index.Profiles.Add(second); store.Save(index);
            Assert.NotEqual(store.DataDirectory(index, index.Profiles[0].Id), store.DataDirectory(index, second.Id));
            Assert.Contains("Profiles", store.DataDirectory(index, second.Id));
        }
        finally { try { Directory.Delete(directory, true); } catch { } }
    }
}