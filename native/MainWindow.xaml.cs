using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using LedgerX.Models;
using LedgerX.Services;
using LedgerX.ViewModels;
using LedgerX.Views;
using Microsoft.Win32;

namespace LedgerX;

public partial class MainWindow : Window
{
    private readonly LedgerStore _store = new();
    private readonly ThemeService _themes = new();
    private readonly MainViewModel _vm;
    private readonly Dictionary<string, Grid> _pages;

    public MainWindow()
    {
        InitializeComponent();
        _vm = new MainViewModel(_store); DataContext = _vm;
        _pages = new() { ["overview"]=OverviewPage, ["metrics"]=MetricsPage, ["records"]=RecordsPage, ["analysis"]=AnalysisPage, ["data"]=DataPage };
        try { if (!string.IsNullOrWhiteSpace(_vm.State.CustomThemePath) && File.Exists(_vm.State.CustomThemePath)) _themes.ApplyCssFile(_vm.State.CustomThemePath); else _themes.ApplyBuiltIn(_vm.State.ThemeName); } catch { _themes.ApplyBuiltIn("暖铜"); }
        DataPathText.Text = $"本地数据位置：{_store.DataFile}";
        UpdateAnalysis();
        ContentRendered += CaptureIfRequested;
    }

    private void NavigateClick(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: string page } && _pages.ContainsKey(page))
        { foreach (var item in _pages.Values) item.Visibility=Visibility.Collapsed; _pages[page].Visibility=Visibility.Visible; if(page=="analysis") UpdateAnalysis(); }
    }

    private void AddClick(object sender, RoutedEventArgs e) => OpenAdd();
    private void MetricCardClick(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource is DependencyObject source && FindParent<Button>(source) is not null) return;
        if ((sender as FrameworkElement)?.DataContext is not MetricItem { CanRecord:true } metric) return;
        if (metric.IsCustom) { OpenAdd(FinanceRecordType.CustomIncrease, metric.Id, metric.Name); return; }
        var type = metric.Id switch { "fixedcost"=>FinanceRecordType.FixedCost, "variablecost"=>FinanceRecordType.VariableCost, "fixedassets"=>FinanceRecordType.FixedAssetPurchase, "payables"=>FinanceRecordType.PayableCreated, _=>FinanceRecordType.Income };
        OpenAdd(type);
    }
    private void OpenAdd(FinanceRecordType? type=null, string? customMetricId=null, string? customMetricName=null)
    { var dialog=new AddRecordWindow(type,customMetricId,customMetricName){Owner=this}; if(dialog.ShowDialog()==true && dialog.Result is not null){_vm.AddRecord(dialog.Result);UpdateAnalysis();} }
    private void PrivacyClick(object sender, RoutedEventArgs e) => _vm.ToggleAllPrivacy();
    private void MetricPrivacyClick(object sender, RoutedEventArgs e) { if((sender as Button)?.Tag is MetricItem metric) _vm.ToggleMetricPrivacy(metric); }
    private void MetricEnabledChanged(object sender, RoutedEventArgs e) { if(IsLoaded && sender is CheckBox { Tag:MetricItem metric, IsChecked:bool enabled}) _vm.SetMetricEnabled(metric,enabled); }
    private void AddMetricClick(object sender, RoutedEventArgs e)
    { var dialog=new AddMetricWindow{Owner=this}; if(dialog.ShowDialog()==true && dialog.Result is not null) _vm.AddCustomMetric(dialog.Result); }
    private void DeleteMetricClick(object sender, RoutedEventArgs e)
    { if((sender as Button)?.Tag is MetricItem { IsCustom:true } metric && MessageBox.Show(this,$"删除指标“{metric.Name}”及其全部记录？","删除指标",MessageBoxButton.YesNo,MessageBoxImage.Warning)==MessageBoxResult.Yes) _vm.DeleteCustomMetric(metric); }
    private void DeleteRecordClick(object sender, RoutedEventArgs e)
    { if(AllRecordsGrid.SelectedItem is FinanceRecord record && MessageBox.Show(this,"确定删除这条记录？","删除记录",MessageBoxButton.YesNo,MessageBoxImage.Question)==MessageBoxResult.Yes){_vm.DeleteRecord(record);UpdateAnalysis();} }
    private void ThemeClick(object sender, RoutedEventArgs e) { var d=new ThemeWindow(_themes,_vm.State){Owner=this}; if(d.ShowDialog()==true) _vm.Persist(); }
    private void BackupClick(object sender, RoutedEventArgs e)
    { var d=new SaveFileDialog{Filter="LedgerX 备份 (*.json)|*.json",FileName=$"LedgerX-backup-{DateTime.Today:yyyyMMdd}.json"}; if(d.ShowDialog(this)==true){_store.Backup(d.FileName,_vm.State);MessageBox.Show(this,"备份已保存。","LedgerX");} }
    private void RestoreClick(object sender, RoutedEventArgs e)
    { var d=new OpenFileDialog{Filter="LedgerX 备份 (*.json)|*.json"}; if(d.ShowDialog(this)==true && MessageBox.Show(this,"恢复会替换当前数据，继续吗？","恢复备份",MessageBoxButton.YesNo,MessageBoxImage.Warning)==MessageBoxResult.Yes) try{_vm.ReplaceState(_store.Restore(d.FileName));UpdateAnalysis();}catch(Exception ex){MessageBox.Show(this,$"无法恢复：{ex.Message}","LedgerX");} }

    private void UpdateAnalysis()
    {
        var records=_vm.State.Records; var income=records.Where(x=>x.Type==FinanceRecordType.Income).Sum(x=>x.Amount);
        var fixedCost=records.Where(x=>x.Type==FinanceRecordType.FixedCost).Sum(x=>x.Amount); var variableCost=records.Where(x=>x.Type==FinanceRecordType.VariableCost).Sum(x=>x.Amount);
        var ownIncome=records.Where(x=>x.Type==FinanceRecordType.Income && !x.Category.Contains("父母")).Sum(x=>x.Amount);
        var dependency=income==0?0:(income-ownIncome)/income*100;
        AnalysisText.Text = records.Count==0 ? "还没有记录。先录入一笔收入或成本，分析会立即出现。" : $"累计流入 ¥ {income:N2}，固定成本 ¥ {fixedCost:N2}，可变成本 ¥ {variableCost:N2}。\n固定成本占全部成本的 {(fixedCost+variableCost==0?0:fixedCost/(fixedCost+variableCost)*100):N1}%；按“父母”分类识别的财务依赖度约为 {dependency:N1}%。\n建议：优先保证现金安全垫，再观察固定成本是否持续挤压资金链。";
    }

    private static T? FindParent<T>(DependencyObject child) where T:DependencyObject { var p=VisualTreeHelper.GetParent(child); while(p is not null && p is not T)p=VisualTreeHelper.GetParent(p); return p as T; }
    private void CaptureIfRequested(object? sender, EventArgs e)
    {
        var args=Environment.GetCommandLineArgs(); var i=Array.IndexOf(args,"--screenshot"); if(i<0 || i+1>=args.Length)return;
        var pageIndex=Array.IndexOf(args,"--page");
        if(pageIndex>=0 && pageIndex+1<args.Length && _pages.TryGetValue(args[pageIndex+1],out var requestedPage))
        { foreach(var page in _pages.Values) page.Visibility=Visibility.Collapsed; requestedPage.Visibility=Visibility.Visible; }
        Dispatcher.InvokeAsync(()=>{var path=Path.GetFullPath(args[i+1]);var dpi=VisualTreeHelper.GetDpi(this);var bitmap=new RenderTargetBitmap((int)(ActualWidth*dpi.DpiScaleX),(int)(ActualHeight*dpi.DpiScaleY),96*dpi.DpiScaleX,96*dpi.DpiScaleY,PixelFormats.Pbgra32);bitmap.Render(this);var encoder=new PngBitmapEncoder();encoder.Frames.Add(BitmapFrame.Create(bitmap));using var stream=File.Create(path);encoder.Save(stream);Close();},System.Windows.Threading.DispatcherPriority.ApplicationIdle);
    }
}
