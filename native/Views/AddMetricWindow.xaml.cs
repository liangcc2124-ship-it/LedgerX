using System.Windows;
using LedgerX.Models;

namespace LedgerX.Views;

public partial class AddMetricWindow : Window
{
    private sealed record FormatOption(string Label, MetricDisplayFormat Value) { public override string ToString() => Label; }
    public CustomMetricDefinition? Result { get; private set; }

    public AddMetricWindow()
    {
        InitializeComponent();
        FormatBox.ItemsSource = new[] { new FormatOption("金额（¥）", MetricDisplayFormat.Currency),
            new FormatOption("数值", MetricDisplayFormat.Number), new FormatOption("百分比（%）", MetricDisplayFormat.Percent) };
        FormatBox.SelectedIndex = 0;
    }

    private void CreateClick(object sender, RoutedEventArgs e)
    {
        var name = NameBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(name)) { MessageBox.Show(this, "请输入指标名称。", "无法创建", MessageBoxButton.OK, MessageBoxImage.Information); return; }
        Result = new CustomMetricDefinition { Name=name, Subtitle=string.IsNullOrWhiteSpace(SubtitleBox.Text) ? "自定义指标" : SubtitleBox.Text.Trim(),
            DisplayFormat=((FormatOption)FormatBox.SelectedItem).Value };
        DialogResult = true;
    }
    private void CancelClick(object sender, RoutedEventArgs e) => DialogResult = false;
}
