using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace LedgerX.Models;

public sealed class MetricItem : INotifyPropertyChanged
{
    private string _displayValue = "¥ 0.00";
    private bool _isHidden;
    private bool _isEnabled = true;

    public required string Id { get; init; }
    public required string Name { get; init; }
    public required string Glyph { get; init; }
    public required string Subtitle { get; init; }
    public bool CanRecord { get; init; }
    public bool IsCustom { get; init; }
    public bool CanDelete => IsCustom;
    public string ManagementHint => IsCustom ? "点击总览卡片可录入增加或减少" : "内置联动指标";
    public MetricDisplayFormat DisplayFormat { get; init; } = MetricDisplayFormat.Currency;
    public string DisplayValue { get => _displayValue; set { _displayValue = value; Notify(); } }
    public bool IsHidden { get => _isHidden; set { _isHidden = value; Notify(); } }
    public bool IsEnabled { get => _isEnabled; set { _isEnabled = value; Notify(); } }

    public event PropertyChangedEventHandler? PropertyChanged;
    private void Notify([CallerMemberName] string? name = null) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
