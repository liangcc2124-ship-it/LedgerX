using LedgerX.Models;

namespace LedgerX.Services;

public sealed record DateRange(DateTime Start, DateTime End, string Label, bool IsOpenPeriod = false);
public sealed record MetricValue(string Id, decimal? Value, string Status, string Formula, IReadOnlyList<Guid> Evidence);

public sealed class MetricEngine
{
    public static DateRange Range(string period, DateTime? anchor = null, DateTime? customStart = null, DateTime? customEnd = null)
    {
        var day = (anchor ?? DateTime.Today).Date;
        return period switch
        {
            "day" => new(day, day, day.ToString("yyyy-MM-dd"), day == DateTime.Today),
            "week" => Week(day),
            "month" => new(new(day.Year, day.Month, 1), new(day.Year, day.Month, DateTime.DaysInMonth(day.Year, day.Month)), day.ToString("yyyy-MM"), day.Year == DateTime.Today.Year && day.Month == DateTime.Today.Month),
            "lastMonth" => Range("month", new DateTime(day.Year, day.Month, 1).AddMonths(-1)),
            "year" => new(new(day.Year, 1, 1), new(day.Year, 12, 31), day.Year.ToString(), day.Year == DateTime.Today.Year),
            "custom" when customStart is not null && customEnd is not null => new(customStart.Value.Date, customEnd.Value.Date, $"{customStart:yyyy-MM-dd} 至 {customEnd:yyyy-MM-dd}"),
            _ => new(DateTime.MinValue.Date, day, "全部记录")
        };
    }

    private static DateRange Week(DateTime day)
    {
        var start = day.AddDays(-(((int)day.DayOfWeek + 6) % 7));
        return new(start, start.AddDays(6), $"{start:MM-dd} 至 {start.AddDays(6):MM-dd}", day >= start && day <= start.AddDays(6));
    }

    public Dictionary<string, MetricValue> Calculate(IEnumerable<FinanceRecord> source, DateRange range, IEnumerable<CustomMetricDefinition>? customMetrics = null, int burnMonths = 3, decimal safetyTarget = 3000m)
    {
        var records = source.Where(x => x.DeletedAt is null).ToList();
        var effectiveEnd = range.End > DateTime.Today ? DateTime.Today : range.End;
        var period = records.Where(x => x.Date.Date >= range.Start && x.Date.Date <= effectiveEnd).ToList();
        var point = records.Where(x => x.Date.Date <= effectiveEnd).ToList();
        decimal Sum(IEnumerable<FinanceRecord> rows, FinanceRecordType type) => rows.Where(x => x.Type == type).Sum(x => x.Amount);
        List<Guid> Ids(IEnumerable<FinanceRecord> rows, params FinanceRecordType[] types) => rows.Where(x => types.Contains(x.Type)).Select(x => x.Id).ToList();
        var inflow = Sum(period, FinanceRecordType.Income);
        var fixedCost = Sum(period, FinanceRecordType.FixedCost);
        var variableCost = Sum(period, FinanceRecordType.VariableCost);
        var assetPurchasesPeriod = Sum(period, FinanceRecordType.FixedAssetPurchase);
        var payablePaymentsPeriod = Sum(period, FinanceRecordType.PayablePayment);
        var outflow = fixedCost + variableCost + assetPurchasesPeriod + payablePaymentsPeriod;
        var cash = Sum(point, FinanceRecordType.Income) - Sum(point, FinanceRecordType.FixedCost) - Sum(point, FinanceRecordType.VariableCost) - Sum(point, FinanceRecordType.FixedAssetPurchase) - Sum(point, FinanceRecordType.PayablePayment);
        var fixedAssets = Sum(point, FinanceRecordType.FixedAssetPurchase);
        var payables = Math.Max(0, Sum(point, FinanceRecordType.PayableCreated) - Sum(point, FinanceRecordType.PayablePayment));
        var totalAssets = cash + fixedAssets;
        var cost = fixedCost + variableCost;
        var upcomingPayables = records.Where(x => x.Date.Date > effectiveEnd && x.Date.Date <= effectiveEnd.AddDays(7) && x.Type == FinanceRecordType.PayableCreated).Sum(x => x.Amount);
        var nonEssential = period.Where(x => x.IsNonEssential && x.Type is FinanceRecordType.FixedCost or FinanceRecordType.VariableCost).Sum(x => x.Amount);
        var ownIncome = period.Where(x => x.Type == FinanceRecordType.Income && x.IsSelfGeneratedIncome).Sum(x => x.Amount);
        var parentIncome = period.Where(x => x.Type == FinanceRecordType.Income && x.IncomeSource == "父母支持").Sum(x => x.Amount);
        var sourceGroups = period.Where(x => x.Type == FinanceRecordType.Income).GroupBy(x => x.IncomeSource ?? "未知").Select(x => x.Sum(r => r.Amount)).ToList();
        var completedMonthStart = new DateTime(effectiveEnd.Year, effectiveEnd.Month, 1).AddMonths(-burnMonths);
        var completedMonthEnd = new DateTime(effectiveEnd.Year, effectiveEnd.Month, 1).AddDays(-1);
        var burnRows = records.Where(x => x.Date.Date >= completedMonthStart && x.Date.Date <= completedMonthEnd && x.Type is FinanceRecordType.FixedCost or FinanceRecordType.VariableCost).ToList();
        var distinctBurnMonths = burnRows.Select(x => (x.Date.Year, x.Date.Month)).Distinct().Count();
        decimal? burn = distinctBurnMonths == burnMonths ? burnRows.Sum(x => x.Amount) / burnMonths : null;
        decimal? runway = burn is > 0 ? Math.Max(0, cash) / burn : null;
        var allPointIds = Ids(point, FinanceRecordType.Income, FinanceRecordType.FixedCost, FinanceRecordType.VariableCost, FinanceRecordType.FixedAssetPurchase, FinanceRecordType.PayablePayment);
        var result = new Dictionary<string, MetricValue>
        {
            ["inflow"] = Ready("inflow", inflow, "Σ 现金流入", Ids(period, FinanceRecordType.Income)),
            ["outflow"] = Ready("outflow", outflow, "固定成本 + 可变成本 + 固定资产购置 + 应付账款支付", Ids(period, FinanceRecordType.FixedCost, FinanceRecordType.VariableCost, FinanceRecordType.FixedAssetPurchase, FinanceRecordType.PayablePayment)),
            ["netflow"] = Ready("netflow", inflow - outflow, "现金流入 − 现金流出", Ids(period, FinanceRecordType.Income, FinanceRecordType.FixedCost, FinanceRecordType.VariableCost, FinanceRecordType.FixedAssetPurchase, FinanceRecordType.PayablePayment)),
            ["cash"] = Ready("cash", cash, "截至期末累计流入 − 累计现金流出", allPointIds),
            ["liquidity"] = Ready("liquidity", cash, "可立即使用的现金储备", allPointIds),
            ["fixedcost"] = Ready("fixedcost", fixedCost, "Σ 固定成本", Ids(period, FinanceRecordType.FixedCost)),
            ["variablecost"] = Ready("variablecost", variableCost, "Σ 可变成本", Ids(period, FinanceRecordType.VariableCost)),
            ["totalcost"] = Ready("totalcost", cost, "固定成本 + 可变成本", Ids(period, FinanceRecordType.FixedCost, FinanceRecordType.VariableCost)),
            ["nonessential"] = Ready("nonessential", nonEssential, "Σ 标记为非必要的成本", period.Where(x => x.IsNonEssential).Select(x => x.Id).ToList()),
            ["nonessentialratio"] = cost == 0 ? Missing("nonessentialratio", "not-computable", "非必要成本 ÷ 总成本") : Ready("nonessentialratio", nonEssential / cost * 100m, "非必要成本 ÷ 总成本 × 100%", period.Where(x => x.IsNonEssential).Select(x => x.Id).ToList()),
            ["upcomingpayables"] = Ready("upcomingpayables", upcomingPayables, "未来 7 天新增应付款", records.Where(x => x.Date.Date > effectiveEnd && x.Date.Date <= effectiveEnd.AddDays(7) && x.Type == FinanceRecordType.PayableCreated).Select(x => x.Id).ToList()),
            ["fixedassets"] = Ready("fixedassets", fixedAssets, "截至期末 Σ 固定资产购置", Ids(point, FinanceRecordType.FixedAssetPurchase)),
            ["payables"] = Ready("payables", payables, "截至期末新增应付 − 已支付应付", Ids(point, FinanceRecordType.PayableCreated, FinanceRecordType.PayablePayment)),
            ["assets"] = Ready("assets", totalAssets, "现金储备 + 固定资产", allPointIds.Concat(Ids(point, FinanceRecordType.FixedAssetPurchase)).Distinct().ToList()),
            ["netassets"] = Ready("netassets", totalAssets - payables, "总资产 − 应付账款", point.Select(x => x.Id).ToList()),
            ["burn"] = burn is null ? Missing("burn", "insufficient", "最近 3 个完整月平均生活成本") : Ready("burn", burn.Value, "最近 3 个完整月生活成本 ÷ 3", burnRows.Select(x => x.Id).ToList()),
            ["runway"] = burn is null ? Missing("runway", "insufficient", "现金储备 ÷ Burn Rate") : burn == 0 ? Missing("runway", "no-burn", "暂无消耗") : Ready("runway", runway!.Value, "现金储备 ÷ Burn Rate", allPointIds.Concat(burnRows.Select(x => x.Id)).Distinct().ToList()),
            ["chain"] = burn is null ? Missing("chain", "insufficient", "现金储备 ÷ Burn Rate") : burn == 0 ? Missing("chain", "no-burn", "暂无消耗") : Ready("chain", runway!.Value, "现金储备 ÷ Burn Rate", allPointIds.Concat(burnRows.Select(x => x.Id)).Distinct().ToList()),
            ["dependency"] = inflow == 0 ? Missing("dependency", "not-computable", "父母支持收入 ÷ 总收入") : Ready("dependency", parentIncome / inflow * 100m, "父母支持收入 ÷ 总收入 × 100%", Ids(period, FinanceRecordType.Income)),
            ["concentration"] = inflow == 0 ? Missing("concentration", "not-computable", "最大单一收入来源 ÷ 总收入") : Ready("concentration", sourceGroups.DefaultIfEmpty(0).Max() / inflow * 100m, "最大单一收入来源 ÷ 总收入 × 100%", Ids(period, FinanceRecordType.Income)),
            ["selfsufficiency"] = outflow == 0 ? Missing("selfsufficiency", "not-computable", "自主收入 ÷ 现金流出") : Ready("selfsufficiency", ownIncome / outflow * 100m, "自主收入 ÷ 现金流出 × 100%", period.Where(x => x.Type == FinanceRecordType.Income && x.IsSelfGeneratedIncome).Select(x => x.Id).Concat(Ids(period, FinanceRecordType.FixedCost, FinanceRecordType.VariableCost, FinanceRecordType.FixedAssetPurchase, FinanceRecordType.PayablePayment)).ToList()),
            ["safetycoverage"] = safetyTarget <= 0 ? Missing("safetycoverage", "not-computable", "现金储备 ÷ 安全垫目标") : Ready("safetycoverage", cash / safetyTarget * 100m, "现金储备 ÷ 安全垫目标 × 100%", allPointIds)
        };
        foreach (var custom in customMetrics ?? [])
        {
            var rows = period.Where(x => x.CustomMetricId == custom.Id).ToList();
            result[custom.Id] = Ready(custom.Id, rows.Sum(x => x.Type == FinanceRecordType.CustomDecrease ? -x.Amount : x.Amount), "Σ 增加 − Σ 减少", rows.Select(x => x.Id).ToList());
        }
        return result;
    }

    private static MetricValue Ready(string id, decimal value, string formula, IReadOnlyList<Guid> evidence) => new(id, value, "ready", formula, evidence);
    private static MetricValue Missing(string id, string status, string formula) => new(id, null, status, formula, []);
}