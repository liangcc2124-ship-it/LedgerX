using LedgerX.Models;

namespace LedgerX.Services;

public sealed class AllocationEngine(FormulaEngine? formulas = null)
{
    private readonly FormulaEngine _formulas = formulas ?? new FormulaEngine();

    public decimal RecognizedAmount(
        AllocationPlan plan,
        DateTime reportStart,
        DateTime reportEndInclusive,
        FormulaDefinition? customFormula = null)
    {
        Validate(plan, customFormula);
        var reportEndExclusive = reportEndInclusive.Date.AddDays(1);
        var start = Max(plan.ServiceStart.Date, reportStart.Date);
        var end = Min(plan.ServiceEndExclusive.Date, reportEndExclusive);
        if (end <= start) return 0m;

        return plan.RecognitionMethod switch
        {
            RecognitionMethod.Immediate => reportStart.Date <= plan.ServiceStart.Date && reportEndExclusive > plan.ServiceStart.Date ? plan.Amount : 0m,
            RecognitionMethod.StraightLineDaily => plan.Amount * (end - start).Days / (plan.ServiceEndExclusive.Date - plan.ServiceStart.Date).Days,
            RecognitionMethod.NaturalMonth => NaturalMonthAmount(plan, start, end),
            RecognitionMethod.PreviousPeriod => reportStart.Date <= plan.ServiceStart.Date.AddDays(-1) && reportEndExclusive > plan.ServiceStart.Date.AddDays(-1) ? plan.Amount : 0m,
            RecognitionMethod.CustomFormula => EvaluateCustom(plan, start, end, customFormula!),
            _ => throw new BridgeException("allocation_method_invalid", "不支持的分摊方法。")
        };
    }

    public IReadOnlyList<AllocationSlice> BuildSchedule(
        AllocationPlan plan,
        string unit,
        FormulaDefinition? customFormula = null,
        int currencyPrecision = 2)
    {
        Validate(plan, customFormula);
        var boundaries = BuildBoundaries(plan.ServiceStart.Date, plan.ServiceEndExclusive.Date, unit).ToList();
        var slices = new List<AllocationSlice>(boundaries.Count);
        decimal roundedTotal = 0m;
        for (var index = 0; index < boundaries.Count; index++)
        {
            var (start, end) = boundaries[index];
            var exact = RecognizedAmount(plan, start, end.AddDays(-1), customFormula);
            var amount = index == boundaries.Count - 1 && plan.RecognitionMethod != RecognitionMethod.CustomFormula
                ? plan.Amount - roundedTotal
                : Math.Round(exact, currencyPrecision, MidpointRounding.AwayFromZero);
            roundedTotal += amount;
            slices.Add(new AllocationSlice(start, end, amount));
        }
        return slices;
    }
    public DepreciationResult BuildDepreciationSchedule(
        FixedAsset asset,
        DateTime reportEndInclusive,
        FormulaDefinition? customFormula = null,
        IReadOnlyDictionary<DateTime, decimal>? unitsByPeriod = null,
        int currencyPrecision = 2)
    {
        ValidateAsset(asset, customFormula, currencyPrecision);
        var endExclusive = Min(reportEndInclusive.Date.AddDays(1), asset.InServiceDate.Date.AddMonths(asset.UsefulLifeMonths));
        if (asset.DisposedAt is { } disposed) endExclusive = Min(endExclusive, disposed.Date.AddDays(1));
        var baseValue = asset.Cost - asset.SalvageValue;
        var accumulated = Math.Clamp(asset.AccumulatedDepreciation, 0m, baseValue);
        if (endExclusive <= asset.InServiceDate.Date || accumulated >= baseValue)
            return new DepreciationResult(0m, accumulated, asset.Cost - accumulated, asset.Method.ToString(), []);

        var slices = new List<AllocationSlice>();
        var cursor = asset.InServiceDate.Date;
        for (var periodIndex = 1; cursor < endExclusive && accumulated < baseValue; periodIndex++)
        {
            var end = Min(cursor.AddMonths(1), endExclusive);
            var units = unitsByPeriod?.GetValueOrDefault(cursor, 0m) ?? 0m;
            var exact = CalculateDepreciation(asset, accumulated, periodIndex, units, customFormula);
            var amount = Math.Min(baseValue - accumulated, Math.Max(0m, Math.Round(exact, currencyPrecision, MidpointRounding.AwayFromZero)));
            slices.Add(new AllocationSlice(cursor, end, amount));
            accumulated += amount;
            cursor = end;
        }
        return new DepreciationResult(slices.Sum(x => x.Amount), accumulated, asset.Cost - accumulated, asset.Method.ToString(), slices);
    }

    private decimal CalculateDepreciation(FixedAsset asset, decimal accumulated, int periodIndex, decimal unitsUsed, FormulaDefinition? customFormula)
    {
        var baseValue = asset.Cost - asset.SalvageValue;
        var bookValue = asset.Cost - accumulated;
        return asset.Method switch
        {
            DepreciationMethod.StraightLine => baseValue / asset.UsefulLifeMonths,
            DepreciationMethod.UnitsOfProduction => baseValue * unitsUsed / asset.TotalProductionUnits,
            DepreciationMethod.DoubleDecliningBalance => bookValue * 2m / asset.UsefulLifeMonths,
            DepreciationMethod.SumOfYearsDigits => baseValue * (asset.UsefulLifeMonths - periodIndex + 1m) / (asset.UsefulLifeMonths * (asset.UsefulLifeMonths + 1m) / 2m),
            DepreciationMethod.CustomFormula => _formulas.Evaluate(customFormula!, new Dictionary<string, decimal>
            {
                ["cost"] = asset.Cost, ["salvageValue"] = asset.SalvageValue, ["depreciableBase"] = baseValue,
                ["usefulLifeMonths"] = asset.UsefulLifeMonths, ["elapsedMonths"] = periodIndex, ["periodIndex"] = periodIndex,
                ["bookValue"] = bookValue, ["totalUnits"] = asset.TotalProductionUnits, ["unitsUsed"] = unitsUsed
            }),
            _ => throw new BridgeException("depreciation_method_invalid", "不支持的折旧方法。")
        };
    }


    public static DateTime InferServiceEnd(DateTime start, BillingCadence cadence) => cadence switch
    {
        BillingCadence.Daily => start.Date.AddDays(1),
        BillingCadence.Weekly => start.Date.AddDays(7),
        BillingCadence.Monthly => start.Date.AddMonths(1),
        BillingCadence.Yearly => start.Date.AddYears(1),
        _ => throw new BridgeException("service_period_required", "自定义周期必须提供服务结束日。")
    };

    private decimal EvaluateCustom(AllocationPlan plan, DateTime overlapStart, DateTime overlapEnd, FormulaDefinition formula)
    {
        var totalDays = (plan.ServiceEndExclusive.Date - plan.ServiceStart.Date).Days;
        var overlapDays = (overlapEnd - overlapStart).Days;
        var variables = new Dictionary<string, decimal>
        {
            ["totalAmount"] = plan.Amount,
            ["totalDays"] = totalDays,
            ["overlapDays"] = overlapDays,
            ["elapsedDays"] = (overlapEnd - plan.ServiceStart.Date).Days,
            ["remainingDays"] = (plan.ServiceEndExclusive.Date - overlapEnd).Days
        };
        return _formulas.Evaluate(formula, variables);
    }

    private static decimal NaturalMonthAmount(AllocationPlan plan, DateTime overlapStart, DateTime overlapEnd)
    {
        var monthCount = CountTouchedMonths(plan.ServiceStart.Date, plan.ServiceEndExclusive.Date);
        decimal total = 0m;
        for (var month = new DateTime(plan.ServiceStart.Year, plan.ServiceStart.Month, 1);
             month < plan.ServiceEndExclusive.Date;
             month = month.AddMonths(1))
        {
            var bucketStart = Max(month, plan.ServiceStart.Date);
            var bucketEnd = Min(month.AddMonths(1), plan.ServiceEndExclusive.Date);
            if (bucketStart < overlapEnd && bucketEnd > overlapStart)
                total += plan.Amount / monthCount;
        }
        return total;
    }

    private static int CountTouchedMonths(DateTime start, DateTime endExclusive)
    {
        var end = endExclusive.AddDays(-1);
        return (end.Year - start.Year) * 12 + end.Month - start.Month + 1;
    }

    private static IEnumerable<(DateTime Start, DateTime End)> BuildBoundaries(DateTime start, DateTime end, string unit)
    {
        var cursor = start;
        while (cursor < end)
        {
            var next = unit switch
            {
                "day" => cursor.AddDays(1),
                "week" => cursor.AddDays(7),
                "month" => cursor.AddMonths(1),
                "year" => cursor.AddYears(1),
                _ => throw new BridgeException("allocation_unit_invalid", "分摊单位必须是 day、week、month 或 year。")
            };
            yield return (cursor, Min(next, end));
            cursor = next;
        }
    }

    private void Validate(AllocationPlan plan, FormulaDefinition? customFormula)
    {
        var fields = new Dictionary<string, string>();
        if (plan.Amount <= 0) fields["amount"] = "金额必须大于 0。";
        if (plan.ServiceEndExclusive.Date <= plan.ServiceStart.Date) fields["serviceEndExclusive"] = "服务结束日必须晚于开始日，且结束日不包含在服务期间内。";
        if (plan.RecognitionMethod == RecognitionMethod.CustomFormula && customFormula is null) fields["formulaId"] = "自定义分摊需要有效公式。";
        if (customFormula is not null && customFormula.Scope != FormulaScope.Allocation) fields["formulaId"] = "公式作用域不是 Allocation。";
        if (fields.Count > 0) throw new BridgeException("allocation_invalid", "分摊计划无效。", fields);
        if (customFormula is not null)
        {
            var validation = _formulas.Validate(customFormula);
            if (!validation.IsValid) throw new BridgeException("formula_invalid", "分摊公式无效。", new Dictionary<string, string> { ["rootExpression"] = string.Join("；", validation.Errors) });
        }
    }

    private static DateTime Min(DateTime left, DateTime right) => left <= right ? left : right;
    private void ValidateAsset(FixedAsset asset, FormulaDefinition? customFormula, int currencyPrecision)
    {
        var fields = new Dictionary<string, string>();
        if (asset.Cost <= 0) fields["cost"] = "资产原值必须大于 0。";
        if (asset.SalvageValue < 0 || asset.SalvageValue > asset.Cost) fields["salvageValue"] = "残值必须在 0 到资产原值之间。";
        if (asset.UsefulLifeMonths <= 0) fields["usefulLifeMonths"] = "使用寿命必须大于 0。";
        if (asset.Method == DepreciationMethod.UnitsOfProduction && asset.TotalProductionUnits <= 0) fields["totalProductionUnits"] = "工作量法需要大于 0 的预计总工作量。";
        if (asset.Method == DepreciationMethod.CustomFormula && customFormula is null) fields["formulaId"] = "自定义折旧需要有效公式。";
        if (customFormula is not null && customFormula.Scope != FormulaScope.Depreciation) fields["formulaId"] = "公式作用域不是 Depreciation。";
        if (currencyPrecision is < 0 or > 28) fields["currencyPrecision"] = "金额精度必须在 0 到 28 之间。";
        if (fields.Count > 0) throw new BridgeException("depreciation_invalid", "固定资产折旧参数无效。", fields);
        if (customFormula is not null)
        {
            var validation = _formulas.Validate(customFormula);
            if (!validation.IsValid) throw new BridgeException("formula_invalid", "折旧公式无效。", new Dictionary<string, string> { ["rootExpression"] = string.Join("；", validation.Errors) });
        }
    }

    private static DateTime Max(DateTime left, DateTime right) => left >= right ? left : right;
}
