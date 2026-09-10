using LedgerX.Models;

namespace LedgerX.Services;

public sealed record ReportPoint(string Label, decimal Income, decimal Outflow, decimal Net, decimal FixedCost, decimal VariableCost, decimal Assets, decimal Payables, IReadOnlyList<Guid> RecordIds);
public sealed record ReportCategory(string Label, decimal Value, IReadOnlyList<Guid> RecordIds);
public sealed class ReportResult
{
    public string Kind { get; set; } = "month"; public string PeriodLabel { get; set; } = string.Empty; public string GeneratedAt { get; set; } = string.Empty; public string Unit { get; set; } = "人民币元"; public string DataStatus { get; set; } = "empty";
    public Dictionary<string, decimal?> Metrics { get; set; } = []; public Dictionary<string, string> MetricStatus { get; set; } = []; public Dictionary<string, decimal?> Comparison { get; set; } = [];
    public List<ReportPoint> Series { get; set; } = []; public List<ReportCategory> CostCategories { get; set; } = []; public List<ReportCategory> IncomeSources { get; set; } = []; public List<Guid> RecordIds { get; set; } = [];
    public string Summary { get; set; } = string.Empty; public decimal UpcomingPayables { get; set; } public decimal? OpeningCash { get; set; } public decimal? ClosingCash { get; set; } public Guid? LargestRecordId { get; set; }
}

public sealed class ReportEngine(MetricEngine metrics)
{
    public ReportResult Build(LedgerState state, string kind, DateTime? anchor = null)
    {
        var today=(anchor??DateTime.Today).Date;var range=MetricEngine.Range(kind,today);var end=range.End>DateTime.Today?DateTime.Today:range.End;
        var currentRange=new DateRange(range.Start,end,range.Label,range.IsOpenPeriod);var previousAnchor=kind switch{"day"=>today.AddDays(-1),"week"=>today.AddDays(-7),"year"=>today.AddYears(-1),_=>today.AddMonths(-1)};var previousBase=MetricEngine.Range(kind,previousAnchor);var elapsed=(end-range.Start).Days;var previousRange=new DateRange(previousBase.Start,previousBase.Start.AddDays(Math.Min(elapsed,(previousBase.End-previousBase.Start).Days)),previousBase.Label);
        var active=state.Records.Where(x=>x.DeletedAt is null).ToList();var rows=active.Where(x=>x.Date.Date>=currentRange.Start&&x.Date.Date<=currentRange.End).ToList();var current=metrics.Calculate(active,currentRange,state.CustomMetrics,safetyTarget:state.Settings.SafetyBufferTarget);var previous=metrics.Calculate(active,previousRange,state.CustomMetrics,safetyTarget:state.Settings.SafetyBufferTarget);
        bool Out(FinanceRecord x)=>x.Type is FinanceRecordType.FixedCost or FinanceRecordType.VariableCost or FinanceRecordType.FixedAssetPurchase or FinanceRecordType.PayablePayment;
        var groupRows=kind=="day"?active.Where(x=>x.Date.Date>=today.AddDays(-13)&&x.Date.Date<=today).ToList():rows;
        var groups=(kind=="year"?groupRows.GroupBy(x=>new DateTime(x.Date.Year,x.Date.Month,1)):groupRows.GroupBy(x=>x.Date.Date)).OrderBy(x=>x.Key);
        var series=groups.Select(g=>new ReportPoint(kind=="year"?g.Key.ToString("MM月"):g.Key.ToString("MM-dd"),g.Where(x=>x.Type==FinanceRecordType.Income).Sum(x=>x.Amount),g.Where(Out).Sum(x=>x.Amount),g.Where(x=>x.Type==FinanceRecordType.Income).Sum(x=>x.Amount)-g.Where(Out).Sum(x=>x.Amount),g.Where(x=>x.Type==FinanceRecordType.FixedCost).Sum(x=>x.Amount),g.Where(x=>x.Type==FinanceRecordType.VariableCost).Sum(x=>x.Amount),metrics.Calculate(active,MetricEngine.Range("day",g.Key),state.CustomMetrics,safetyTarget:state.Settings.SafetyBufferTarget)["assets"].Value??0,metrics.Calculate(active,MetricEngine.Range("day",g.Key),state.CustomMetrics,safetyTarget:state.Settings.SafetyBufferTarget)["payables"].Value??0,g.Select(x=>x.Id).ToList())).ToList();
        List<ReportCategory> Top(IEnumerable<IGrouping<string,FinanceRecord>> source)=>source.Select(g=>new ReportCategory(g.Key,g.Sum(x=>x.Amount),g.Select(x=>x.Id).ToList())).OrderByDescending(x=>x.Value).ToList() is var all&&all.Count>5?[..all.Take(5),new ReportCategory("其他",all.Skip(5).Sum(x=>x.Value),all.Skip(5).SelectMany(x=>x.RecordIds).ToList())]:all;
        var opening=metrics.Calculate(active,new DateRange(DateTime.MinValue,currentRange.Start.AddDays(-1),"期初"),state.CustomMetrics,safetyTarget:state.Settings.SafetyBufferTarget)["cash"].Value;var closing=current["cash"].Value;
        var result=new ReportResult{Kind=kind,PeriodLabel=$"{(kind=="day"?"日报":kind=="week"?"周报":kind=="year"?"年报":"月报")} · {currentRange.Label}{(range.IsOpenPeriod?"（截至今天）":"")}",GeneratedAt=DateTime.Now.ToString("yyyy-MM-dd HH:mm"),DataStatus=rows.Count==0?"empty":"ready",Series=series,CostCategories=Top(rows.Where(x=>x.Type is FinanceRecordType.FixedCost or FinanceRecordType.VariableCost).GroupBy(x=>string.IsNullOrWhiteSpace(x.Category)?"未分类":x.Category)),IncomeSources=Top(rows.Where(x=>x.Type==FinanceRecordType.Income).GroupBy(x=>x.IncomeSource??"未知")),RecordIds=rows.Select(x=>x.Id).ToList(),UpcomingPayables=active.Where(x=>x.Type==FinanceRecordType.PayableCreated&&x.Date.Date>today&&x.Date.Date<=today.AddDays(7)).Sum(x=>x.Amount),OpeningCash=opening,ClosingCash=closing,LargestRecordId=rows.OrderByDescending(x=>x.Amount).FirstOrDefault()?.Id};
        foreach(var id in new[]{"inflow","outflow","netflow","cash","fixedcost","variablecost","totalcost","nonessential","fixedassets","payables","upcomingpayables","nonessentialratio","assets","netassets","burn","runway","dependency","concentration","selfsufficiency","safetycoverage"}){result.Metrics[id]=current.GetValueOrDefault(id)?.Value;result.MetricStatus[id]=current.GetValueOrDefault(id)?.Status??"not-computable";result.Comparison[id]=previous.GetValueOrDefault(id)?.Value;}
        var net=result.Metrics["netflow"]??0;result.Summary=rows.Count==0?"本周期暂无记录。":net>=0?$"本周期净现金流为正，流入比流出多 {net:N2} 元。":$"本周期净现金流为负，流出比流入多 {Math.Abs(net):N2} 元。";return result;
    }
}