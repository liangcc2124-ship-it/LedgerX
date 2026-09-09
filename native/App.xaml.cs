using System.Windows;
using LedgerX.Models;
using LedgerX.Services;
using LedgerX.ViewModels;

namespace LedgerX;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        if (e.Args.Contains("--smoke-test"))
        {
            var vm = new MainViewModel(new LedgerStore());
            vm.AddRecord(new FinanceRecord { Type=FinanceRecordType.Income, Amount=1000, Category="测试收入" });
            vm.AddRecord(new FinanceRecord { Type=FinanceRecordType.FixedCost, Amount=200, Category="测试固定成本" });
            var custom = vm.AddCustomMetric(new CustomMetricDefinition { Name="储蓄率", Subtitle="测试自定义指标", DisplayFormat=MetricDisplayFormat.Percent });
            vm.AddRecord(new FinanceRecord { Type=FinanceRecordType.CustomIncrease, Amount=25, CustomMetricId=custom.Id, Category="测试指标" });
            var cash = vm.Metrics.First(x=>x.Id=="cash").DisplayValue;
            var customValue = vm.Metrics.First(x=>x.Id==custom.Id).DisplayValue;
            var passed = cash.Contains("800.00") && vm.Metrics.First(x=>x.Id=="assets").DisplayValue.Contains("800.00") && customValue.Contains("25.00%");
            var outputIndex=Array.IndexOf(e.Args,"--output");
            if(outputIndex>=0 && outputIndex+1<e.Args.Length) File.WriteAllText(e.Args[outputIndex+1], $"{{\"passed\":{passed.ToString().ToLowerInvariant()},\"cash\":\"{cash}\",\"customMetric\":\"{customValue}\"}}");
            Shutdown(passed ? 0 : 1); return;
        }
        MainWindow = new MainWindow(); MainWindow.Show();
    }
}
