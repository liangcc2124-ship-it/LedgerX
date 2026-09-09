using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using LedgerX.Models;
using LedgerX.Services;

namespace LedgerX.ViewModels;

public sealed class MainViewModel : INotifyPropertyChanged
{
    private readonly LedgerStore _store;
    public LedgerState State { get; private set; }
    public ObservableCollection<FinanceRecord> Records { get; } = [];
    public ObservableCollection<MetricItem> Metrics { get; } = [];
    public ObservableCollection<MetricItem> VisibleMetrics { get; } = [];
    public string PrivacyButtonText => State.HideAllAmounts ? "显示金额" : "隐藏金额";

    public MainViewModel(LedgerStore store)
    {
        _store = store;
        State = store.Load();
        CreateMetrics();
        ReloadCollections();
    }

    private void CreateMetrics()
    {
        Metrics.Clear();
        Metrics.Add(new() { Id="cash", Name="现金储备", Glyph="\uE8C7", Subtitle="随时可用资金", CanRecord=true });
        Metrics.Add(new() { Id="netflow", Name="净现金流", Glyph="\uE9D2", Subtitle="累计流入 − 流出", CanRecord=true });
        Metrics.Add(new() { Id="assets", Name="总资产", Glyph="\uE825", Subtitle="现金 + 固定资产", CanRecord=true });
        Metrics.Add(new() { Id="chain", Name="资金链", Glyph="\uE8AB", Subtitle="当前可支撑月数", CanRecord=false });
        Metrics.Add(new() { Id="fixedcost", Name="固定成本", Glyph="\uE719", Subtitle="周期性、刚性成本", CanRecord=true });
        Metrics.Add(new() { Id="variablecost", Name="可变成本", Glyph="\uE7BF", Subtitle="随使用变化的成本", CanRecord=true });
        Metrics.Add(new() { Id="fixedassets", Name="固定资产", Glyph="\uE7F8", Subtitle="耐用品账面价值", CanRecord=true });
        Metrics.Add(new() { Id="payables", Name="应付账款", Glyph="\uE8C1", Subtitle="尚未支付的义务", CanRecord=true });
        Metrics.Add(new() { Id="runway", Name="Runway", Glyph="\uE916", Subtitle="按当前消耗可维持", CanRecord=false });
        foreach (var definition in State.CustomMetrics)
            Metrics.Add(new() { Id=definition.Id, Name=definition.Name, Glyph="\uE9D2", Subtitle=definition.Subtitle,
                CanRecord=true, IsCustom=true, DisplayFormat=definition.DisplayFormat });
        if (State.EnabledMetrics.Count == 0)
            foreach (var metric in Metrics) State.EnabledMetrics.Add(metric.Id);
        foreach (var metric in Metrics)
        {
            metric.IsEnabled = State.EnabledMetrics.Contains(metric.Id);
            metric.IsHidden = State.HiddenMetrics.Contains(metric.Id);
        }
    }

    public void AddRecord(FinanceRecord record) { State.Records.Add(record); SaveAndRefresh(); }
    public void DeleteRecord(FinanceRecord record) { State.Records.RemoveAll(x => x.Id == record.Id); SaveAndRefresh(); }

    public MetricItem AddCustomMetric(CustomMetricDefinition definition)
    {
        State.CustomMetrics.Add(definition);
        State.EnabledMetrics.Add(definition.Id);
        var metric = new MetricItem { Id=definition.Id, Name=definition.Name, Glyph="\uE9D2", Subtitle=definition.Subtitle,
            CanRecord=true, IsCustom=true, DisplayFormat=definition.DisplayFormat, IsEnabled=true };
        Metrics.Add(metric); SaveAndRefresh(); return metric;
    }

    public void DeleteCustomMetric(MetricItem metric)
    {
        if (!metric.IsCustom) return;
        State.CustomMetrics.RemoveAll(x => x.Id == metric.Id);
        State.Records.RemoveAll(x => x.CustomMetricId == metric.Id);
        State.EnabledMetrics.Remove(metric.Id); State.HiddenMetrics.Remove(metric.Id);
        Metrics.Remove(metric); SaveAndRefresh();
    }

    public void ToggleAllPrivacy()
    {
        State.HideAllAmounts = !State.HideAllAmounts;
        SaveAndRefresh();
        Notify(nameof(PrivacyButtonText));
    }

    public void ToggleMetricPrivacy(MetricItem metric)
    {
        metric.IsHidden = !metric.IsHidden;
        if (metric.IsHidden) State.HiddenMetrics.Add(metric.Id); else State.HiddenMetrics.Remove(metric.Id);
        SaveAndRefresh();
    }

    public void SetMetricEnabled(MetricItem metric, bool enabled)
    {
        metric.IsEnabled = enabled;
        if (enabled) State.EnabledMetrics.Add(metric.Id); else State.EnabledMetrics.Remove(metric.Id);
        SaveAndRefresh();
    }

    public void ReplaceState(LedgerState state)
    {
        State = state;
        CreateMetrics();
        SaveAndRefresh();
        Notify(nameof(PrivacyButtonText));
    }

    public void Persist() => _store.Save(State);

    private void SaveAndRefresh() { _store.Save(State); ReloadCollections(); }

    private void ReloadCollections()
    {
        Records.Clear();
        foreach (var record in State.Records.OrderByDescending(x => x.Date).ThenByDescending(x => x.CreatedAt))
        { record.IsAmountHidden = State.HideAllAmounts; Records.Add(record); }
        VisibleMetrics.Clear();
        foreach (var metric in Metrics.Where(x => x.IsEnabled)) VisibleMetrics.Add(metric);

        var income = State.Records.Where(x => x.Type == FinanceRecordType.Income).Sum(x => x.Amount);
        var fixedCost = State.Records.Where(x => x.Type == FinanceRecordType.FixedCost).Sum(x => x.Amount);
        var variableCost = State.Records.Where(x => x.Type == FinanceRecordType.VariableCost).Sum(x => x.Amount);
        var purchases = State.Records.Where(x => x.Type == FinanceRecordType.FixedAssetPurchase).Sum(x => x.Amount);
        var payablePayments = State.Records.Where(x => x.Type == FinanceRecordType.PayablePayment).Sum(x => x.Amount);
        var payables = Math.Max(0, State.Records.Where(x => x.Type == FinanceRecordType.PayableCreated).Sum(x => x.Amount) - payablePayments);
        var cash = income - fixedCost - variableCost - purchases - payablePayments;
        var netFlow = income - fixedCost - variableCost - purchases - payablePayments;
        var totalAssets = cash + purchases;
        var months = Math.Max(1, State.Records.Select(x => x.Date).DistinctBy(x => (x.Year, x.Month)).Count());
        var burn = (fixedCost + variableCost + payablePayments) / months;
        var runway = burn > 0 ? Math.Max(0, cash) / burn : (decimal?)null;

        Set("cash", cash); Set("netflow", netFlow); Set("assets", totalAssets);
        Set("fixedcost", fixedCost); Set("variablecost", variableCost); Set("fixedassets", purchases); Set("payables", payables);
        SetText("runway", runway is null ? "—" : $"{runway:N1} 个月");
        SetText("chain", runway is null ? "暂无消耗" : runway < 1 ? "偏紧" : runway < 3 ? "需关注" : "稳健");
        foreach (var definition in State.CustomMetrics)
        {
            var value = State.Records.Where(x => x.CustomMetricId == definition.Id).Sum(x => x.Type == FinanceRecordType.CustomDecrease ? -x.Amount : x.Amount);
            var display = definition.DisplayFormat switch
            {
                MetricDisplayFormat.Number => $"{value:N2}",
                MetricDisplayFormat.Percent => $"{value:N2}%",
                _ => $"¥ {value:N2}"
            };
            SetText(definition.Id, display);
        }
    }

    private void Set(string id, decimal value) => SetText(id, $"¥ {value:N2}");
    private void SetText(string id, string value)
    {
        var metric = Metrics.First(x => x.Id == id);
        metric.DisplayValue = State.HideAllAmounts || metric.IsHidden ? "••••••" : value;
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    private void Notify([CallerMemberName] string? name=null) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
