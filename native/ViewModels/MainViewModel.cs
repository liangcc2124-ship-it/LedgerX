using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using LedgerX.Models;
using LedgerX.Services;

namespace LedgerX.ViewModels;

public sealed class MainViewModel : INotifyPropertyChanged
{
    private static readonly string[] DefaultMetricIds = ["cash","netflow","assets","chain","fixedcost","variablecost","fixedassets","payables","runway"];
    private readonly LedgerStore _store;
    private readonly MetricEngine _metrics = new();
    private readonly WarningEngine _warnings;
    public LedgerState State { get; private set; }
    public ObservableCollection<FinanceRecord> Records { get; } = [];
    public ObservableCollection<MetricItem> Metrics { get; } = [];
    public ObservableCollection<MetricItem> VisibleMetrics { get; } = [];
    public string PrivacyButtonText => State.HideAllAmounts ? "显示金额" : "隐藏金额";

    public MainViewModel(LedgerStore store)
    {
        _store = store; _warnings = new WarningEngine(_metrics); State = store.Load();
        CreateMetrics(); ReloadCollections(); if (!_store.RecoveryRequired) { _warnings.EvaluateAll(State); _store.Save(State); if (_store.TryAutoBackup(State) is not null) _store.Save(State); }
    }

    private void CreateMetrics()
    {
        Metrics.Clear();
        AddMetric("cash","现金储备","随时可用资金",true); AddMetric("netflow","净现金流","所选周期流入 − 流出",true); AddMetric("assets","总资产","现金 + 固定资产",true); AddMetric("chain","资金链","当前可支撑月数",false,MetricDisplayFormat.Number);
        AddMetric("fixedcost","固定成本","周期性、刚性成本",true); AddMetric("variablecost","可变成本","随使用变化的成本",true); AddMetric("fixedassets","固定资产","耐用品账面价值",true); AddMetric("payables","应付账款","尚未支付的义务",true); AddMetric("runway","Runway","按当前消耗可维持",false,MetricDisplayFormat.Number);
        AddMetric("inflow","现金流入","所选周期现金流入",true); AddMetric("outflow","现金流出","所选周期实际支付",true); AddMetric("totalcost","总成本","固定成本 + 可变成本",false); AddMetric("nonessential","非必要成本","可削减的生活成本",false); AddMetric("nonessentialratio","非必要成本占比","非必要成本占总成本比例",false,MetricDisplayFormat.Percent); AddMetric("upcomingpayables","未来七天应付款","未来七天内预计到期",false); AddMetric("burn","现金消耗率","最近 3 个完整月平均成本",false); AddMetric("netassets","净资产","总资产 − 应付账款",false); AddMetric("liquidity","流动性","可立即使用的现金",false); AddMetric("dependency","财务依赖度","父母支持收入占比",false,MetricDisplayFormat.Percent); AddMetric("concentration","收入集中度","最大单一收入来源占比",false,MetricDisplayFormat.Percent); AddMetric("selfsufficiency","自我造血能力","自主收入覆盖现金流出",false,MetricDisplayFormat.Percent); AddMetric("safetycoverage","安全垫覆盖率","现金覆盖安全垫目标",false,MetricDisplayFormat.Percent);
        foreach (var definition in State.CustomMetrics) AddMetric(definition.Id,definition.Name,definition.Subtitle,true,definition.DisplayFormat,true);
        if (State.EnabledMetrics.Count == 0) foreach (var id in DefaultMetricIds) State.EnabledMetrics.Add(id);
        foreach (var metric in Metrics) { metric.IsEnabled = State.EnabledMetrics.Contains(metric.Id); metric.IsHidden = State.HiddenMetrics.Contains(metric.Id); }
    }

    private void AddMetric(string id,string name,string subtitle,bool canRecord,MetricDisplayFormat format=MetricDisplayFormat.Currency,bool custom=false) => Metrics.Add(new(){Id=id,Name=name,Glyph="",Subtitle=subtitle,CanRecord=canRecord,IsCustom=custom,DisplayFormat=format});
    public void AddRecord(FinanceRecord record) { Validate(record); record.CreatedAt=DateTime.Now;record.UpdatedAt=record.CreatedAt;State.Records.Add(record);SaveAndRefresh(); }
    public void UpdateRecord(Guid id, FinanceRecord update) { Validate(update);var record=State.Records.FirstOrDefault(x=>x.Id==id&&x.DeletedAt is null)??throw new InvalidOperationException("找不到要编辑的记录。");record.Date=update.Date;record.Type=update.Type;record.Amount=update.Amount;record.Category=update.Category;record.Account=update.Account;record.CategoryId=update.CategoryId;record.MetricTargetIds=update.MetricTargetIds;record.SettlementMode=update.SettlementMode;record.SettlementDate=update.SettlementDate;record.FinancialAccountId=update.FinancialAccountId;record.ServiceStart=update.ServiceStart;record.ServiceEndExclusive=update.ServiceEndExclusive;record.BillingCadence=update.BillingCadence;record.RecognitionMethod=update.RecognitionMethod;record.AllocationFormulaId=update.AllocationFormulaId;record.Note=update.Note;record.CustomMetricId=update.CustomMetricId;record.IncomeSource=update.IncomeSource;record.IsSelfGeneratedIncome=update.IsSelfGeneratedIncome;record.IsNonEssential=update.IsNonEssential;record.UpdatedAt=DateTime.Now;SaveAndRefresh(); }
    public void DeleteRecord(FinanceRecord record){record.DeletedAt=DateTime.Now;record.UpdatedAt=record.DeletedAt.Value;SaveAndRefresh();}
    public void RestoreRecord(Guid id){var r=State.Records.FirstOrDefault(x=>x.Id==id&&x.DeletedAt is not null)??throw new InvalidOperationException("回收站中找不到该记录。");r.DeletedAt=null;r.UpdatedAt=DateTime.Now;SaveAndRefresh();}
    public void PurgeRecord(Guid id){State.Records.RemoveAll(x=>x.Id==id&&x.DeletedAt is not null);SaveAndRefresh();}
    public MetricItem AddCustomMetric(CustomMetricDefinition definition){if(string.IsNullOrWhiteSpace(definition.Name))throw new InvalidOperationException("指标名称不能为空。");State.CustomMetrics.Add(definition);State.EnabledMetrics.Add(definition.Id);CreateMetrics();SaveAndRefresh();return Metrics.First(x=>x.Id==definition.Id);}
    public void DeleteCustomMetric(MetricItem metric){if(!metric.IsCustom)return;if(State.WarningRules.Any(x=>x.MetricId==metric.Id||x.Conditions.Any(c=>c.MetricId==metric.Id)))throw new InvalidOperationException("该指标正在被预警规则使用，请先删除或修改关联规则。");State.CustomMetrics.RemoveAll(x=>x.Id==metric.Id);State.Records.RemoveAll(x=>x.CustomMetricId==metric.Id);State.EnabledMetrics.Remove(metric.Id);State.HiddenMetrics.Remove(metric.Id);CreateMetrics();SaveAndRefresh();}
    public void ToggleAllPrivacy(){State.HideAllAmounts=!State.HideAllAmounts;SaveAndRefresh();Notify(nameof(PrivacyButtonText));}
    public void ToggleMetricPrivacy(MetricItem metric){metric.IsHidden=!metric.IsHidden;if(metric.IsHidden)State.HiddenMetrics.Add(metric.Id);else State.HiddenMetrics.Remove(metric.Id);SaveAndRefresh();}
    public void SetMetricEnabled(MetricItem metric,bool enabled){metric.IsEnabled=enabled;if(enabled)State.EnabledMetrics.Add(metric.Id);else State.EnabledMetrics.Remove(metric.Id);SaveAndRefresh();}
    public void ReplaceState(LedgerState state){State=state;CreateMetrics();SaveAndRefresh();Notify(nameof(PrivacyButtonText));}
    public void Persist()=>SaveAndRefresh();
    private static void Validate(FinanceRecord record){if(record.Amount<=0)throw new InvalidOperationException("金额或数值必须大于 0。");if(record.Date==default)throw new InvalidOperationException("请选择日期。");if(record.Type is FinanceRecordType.CustomIncrease or FinanceRecordType.CustomDecrease && string.IsNullOrWhiteSpace(record.CustomMetricId))throw new InvalidOperationException("请选择自定义指标。");}
    private void SaveAndRefresh(){if(_store.RecoveryRequired)throw new InvalidOperationException("账本处于只读恢复状态，请先完成恢复。");ReloadCollections();_warnings.EvaluateAll(State);_store.Save(State);if(_store.TryAutoBackup(State) is not null)_store.Save(State);}
    private void ReloadCollections(){var active=State.Records.Where(x=>x.DeletedAt is null).ToList();Records.Clear();foreach(var r in active.OrderByDescending(x=>x.Date).ThenByDescending(x=>x.CreatedAt)){r.IsAmountHidden=State.HideAllAmounts;Records.Add(r);}VisibleMetrics.Clear();foreach(var m in Metrics.Where(x=>x.IsEnabled))VisibleMetrics.Add(m);var values=_metrics.Calculate(active,MetricEngine.Range("all"),State.CustomMetrics,safetyTarget:State.Settings.SafetyBufferTarget,metricDefinitions:State.MetricDefinitions,formulaDefinitions:State.FormulaDefinitions);foreach(var metric in Metrics)SetMetric(metric,values.GetValueOrDefault(metric.Id));}
    private void SetMetric(MetricItem metric,MetricValue? value){if(State.HideAllAmounts||metric.IsHidden){metric.DisplayValue="••••••";return;}if(value?.Value is null){metric.DisplayValue=value?.Status=="no-burn"?"暂无消耗":value?.Status=="insufficient"?"样本不足":"暂无法计算";return;}metric.DisplayValue=metric.DisplayFormat switch{MetricDisplayFormat.Number when metric.Id is "runway" or "chain"=>$"{value.Value:N1} 个月",MetricDisplayFormat.Number=>$"{value.Value:N2}",MetricDisplayFormat.Percent=>$"{value.Value:N1}%",_=>$"¥ {value.Value:N2}"};}
    public event PropertyChangedEventHandler? PropertyChanged;private void Notify([CallerMemberName]string? name=null)=>PropertyChanged?.Invoke(this,new PropertyChangedEventArgs(name));
}