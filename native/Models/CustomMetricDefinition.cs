namespace LedgerX.Models;

public enum MetricDisplayFormat { Currency, Number, Percent }

public sealed class CustomMetricDefinition
{
    public string Id { get; set; } = $"custom-{Guid.NewGuid():N}";
    public string Name { get; set; } = string.Empty;
    public string Subtitle { get; set; } = "自定义指标";
    public MetricDisplayFormat DisplayFormat { get; set; } = MetricDisplayFormat.Currency;
}
