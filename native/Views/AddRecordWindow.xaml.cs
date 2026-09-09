using System.Windows;
using System.Windows.Controls;
using LedgerX.Models;

namespace LedgerX.Views;

public partial class AddRecordWindow : Window
{
    private sealed record TypeOption(string Label, FinanceRecordType Type) { public override string ToString() => Label; }
    private readonly string? _customMetricId;
    public FinanceRecord? Result { get; private set; }

    public AddRecordWindow(FinanceRecordType? preferred = null, string? customMetricId = null, string? customMetricName = null)
    {
        InitializeComponent();
        _customMetricId = customMetricId;
        TypeBox.ItemsSource = customMetricId is not null
            ? new[] { new TypeOption("增加指标", FinanceRecordType.CustomIncrease), new TypeOption("减少指标", FinanceRecordType.CustomDecrease) }
            : new[] { new TypeOption("现金流入", FinanceRecordType.Income), new TypeOption("固定成本", FinanceRecordType.FixedCost),
                new TypeOption("可变成本", FinanceRecordType.VariableCost), new TypeOption("购置固定资产", FinanceRecordType.FixedAssetPurchase),
                new TypeOption("新增应付账款", FinanceRecordType.PayableCreated), new TypeOption("支付应付账款", FinanceRecordType.PayablePayment) };
        if (customMetricId is not null) { Title=$"记录 · {customMetricName}"; AccountBox.Text=customMetricName ?? "自定义指标"; }
        TypeBox.SelectedItem = ((IEnumerable<TypeOption>)TypeBox.ItemsSource).FirstOrDefault(x => x.Type == preferred) ?? TypeBox.Items[0];
    }

    private void TypeChanged(object sender, SelectionChangedEventArgs e)
    {
        if (TypeBox.SelectedItem is not TypeOption item) return;
        ImpactText.Text = item.Type switch {
            FinanceRecordType.Income => "影响：现金储备 ↑、净现金流 ↑、总资产 ↑",
            FinanceRecordType.FixedCost or FinanceRecordType.VariableCost => "影响：现金储备 ↓、净现金流 ↓、对应成本 ↑",
            FinanceRecordType.FixedAssetPurchase => "影响：现金储备 ↓、固定资产 ↑；总资产保持不变",
            FinanceRecordType.PayableCreated => "影响：应付账款 ↑；暂不改变现金",
            FinanceRecordType.CustomIncrease => "影响：此自定义指标增加；不改变其他财务指标",
            FinanceRecordType.CustomDecrease => "影响：此自定义指标减少；不改变其他财务指标",
            _ => "影响：现金储备 ↓、应付账款 ↓"
        };
    }

    private void SaveClick(object sender, RoutedEventArgs e)
    {
        if (!decimal.TryParse(AmountBox.Text, out var amount) || amount <= 0 || DateBox.SelectedDate is null)
        { MessageBox.Show(this, "请输入大于 0 的金额，并选择日期。", "无法保存", MessageBoxButton.OK, MessageBoxImage.Information); return; }
        Result = new FinanceRecord { Type=((TypeOption)TypeBox.SelectedItem).Type, Amount=amount, Date=DateBox.SelectedDate.Value,
            Category=CategoryBox.Text.Trim(), Account=AccountBox.Text.Trim(), Note=NoteBox.Text.Trim(), CustomMetricId=_customMetricId };
        DialogResult = true;
    }
    private void CancelClick(object sender, RoutedEventArgs e) => DialogResult = false;
}
