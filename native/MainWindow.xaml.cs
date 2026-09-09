using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using LedgerX.Models;
using LedgerX.Services;
using LedgerX.ViewModels;
using Microsoft.Web.WebView2.Core;
using Microsoft.Win32;

namespace LedgerX;

public partial class MainWindow : Window
{
    private readonly LedgerStore _store = new();
    private readonly MainViewModel _vm;
    private readonly JsonSerializerOptions _json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        Converters = { new JsonStringEnumConverter() }
    };

    public MainWindow()
    {
        InitializeComponent();
        _vm = new MainViewModel(_store);
        Loaded += async (_, _) => await InitializeWebViewAsync();
    }

    private async Task InitializeWebViewAsync()
    {
        try
        {
            var userData = Path.Combine(_store.DataDirectory, "WebView2");
            var environment = await CoreWebView2Environment.CreateAsync(null, userData);
            await WebView.EnsureCoreWebView2Async(environment);
            WebView.CoreWebView2.Settings.AreDevToolsEnabled = false;
            WebView.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
            WebView.CoreWebView2.Settings.IsStatusBarEnabled = false;
            WebView.CoreWebView2.WebMessageReceived += OnWebMessageReceived;

            var webRoot = Path.Combine(AppContext.BaseDirectory, "web");
            if (!Directory.Exists(webRoot))
                throw new DirectoryNotFoundException($"界面资源不存在：{webRoot}");

            WebView.CoreWebView2.SetVirtualHostNameToFolderMapping(
                "app.ledgerx.local", webRoot, CoreWebView2HostResourceAccessKind.DenyCors);
            WebView.CoreWebView2.NavigationCompleted += OnNavigationCompleted;
            WebView.CoreWebView2.Navigate("https://app.ledgerx.local/index.html");
        }
        catch (Exception ex)
        {
            LoadingPanel.Child = new TextBlock
            {
                Text = $"LedgerX 界面加载失败。\n\n{ex.Message}",
                TextWrapping = TextWrapping.Wrap,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(40)
            };
        }
    }

    private async void OnNavigationCompleted(object? sender, CoreWebView2NavigationCompletedEventArgs e)
    {
        LoadingPanel.Visibility = e.IsSuccess ? Visibility.Collapsed : Visibility.Visible;
        if (!e.IsSuccess) return;
        var args = Environment.GetCommandLineArgs();
        var index = Array.IndexOf(args, "--screenshot");
        if (index < 0 || index + 1 >= args.Length) return;
        await Task.Delay(900);
        var path = Path.GetFullPath(args[index + 1]);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        await using var stream = File.Create(path);
        await WebView.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, stream);
        Close();
    }

    private async void OnWebMessageReceived(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        string id = string.Empty;
        try
        {
            using var document = JsonDocument.Parse(e.WebMessageAsJson);
            var root = document.RootElement;
            id = root.GetProperty("id").GetString() ?? string.Empty;
            var command = root.GetProperty("command").GetString() ?? string.Empty;
            var payload = root.TryGetProperty("payload", out var value) ? value : default;
            await ExecuteCommandAsync(command, payload);
            Respond(id, true, BuildSnapshot(), null);
        }
        catch (Exception ex)
        {
            Respond(id, false, null, ex.Message);
        }
    }

    private Task ExecuteCommandAsync(string command, JsonElement payload)
    {
        switch (command)
        {
            case "getState":
                break;
            case "addRecord":
                _vm.AddRecord(new FinanceRecord
                {
                    Date = DateTime.Parse(payload.GetProperty("date").GetString()!),
                    Type = Enum.Parse<FinanceRecordType>(payload.GetProperty("type").GetString()!),
                    Amount = payload.GetProperty("amount").GetDecimal(),
                    Category = StringValue(payload, "category"),
                    Account = StringValue(payload, "account", "现金储备"),
                    Note = StringValue(payload, "note"),
                    CustomMetricId = NullableString(payload, "customMetricId")
                });
                break;
            case "deleteRecord":
                var recordId = Guid.Parse(payload.GetProperty("id").GetString()!);
                var record = _vm.State.Records.FirstOrDefault(x => x.Id == recordId);
                if (record is not null) _vm.DeleteRecord(record);
                break;
            case "toggleAllPrivacy":
                _vm.ToggleAllPrivacy();
                break;
            case "toggleMetricPrivacy":
                _vm.ToggleMetricPrivacy(FindMetric(payload));
                break;
            case "setMetricEnabled":
                _vm.SetMetricEnabled(FindMetric(payload), payload.GetProperty("enabled").GetBoolean());
                break;
            case "addCustomMetric":
                _vm.AddCustomMetric(new CustomMetricDefinition
                {
                    Name = StringValue(payload, "name"),
                    Subtitle = StringValue(payload, "subtitle", "自定义指标"),
                    DisplayFormat = Enum.Parse<MetricDisplayFormat>(StringValue(payload, "displayFormat", "Currency"))
                });
                break;
            case "deleteCustomMetric":
                _vm.DeleteCustomMetric(FindMetric(payload));
                break;
            case "setTheme":
                _vm.State.ThemeName = StringValue(payload, "name", "暖铜");
                _vm.State.CustomThemeCss = null;
                _vm.State.CustomThemePath = null;
                _vm.Persist();
                break;
            case "backup":
                Backup();
                break;
            case "restore":
                Restore();
                break;
            case "importTheme":
                ImportTheme();
                break;
            case "exportTheme":
                ExportTheme();
                break;
            default:
                throw new InvalidOperationException($"未知操作：{command}");
        }
        return Task.CompletedTask;
    }

    private MetricItem FindMetric(JsonElement payload)
    {
        var id = payload.GetProperty("id").GetString();
        return _vm.Metrics.FirstOrDefault(x => x.Id == id)
            ?? throw new InvalidOperationException("找不到该指标。");
    }

    private void Backup()
    {
        var dialog = new SaveFileDialog { Filter = "LedgerX 备份 (*.json)|*.json", FileName = $"LedgerX-backup-{DateTime.Today:yyyyMMdd}.json" };
        if (dialog.ShowDialog(this) == true) _store.Backup(dialog.FileName, _vm.State);
    }

    private void Restore()
    {
        var dialog = new OpenFileDialog { Filter = "LedgerX 备份 (*.json)|*.json" };
        if (dialog.ShowDialog(this) == true) _vm.ReplaceState(_store.Restore(dialog.FileName));
    }

    private void ImportTheme()
    {
        var dialog = new OpenFileDialog { Filter = "CSS 皮肤 (*.css)|*.css" };
        if (dialog.ShowDialog(this) != true) return;
        var source = File.ReadAllText(dialog.FileName);
        if (Regex.IsMatch(source, @"url\s*\(|@import|expression\s*\(", RegexOptions.IgnoreCase))
            throw new InvalidDataException("皮肤不能包含远程资源或可执行表达式。");
        var declarations = Regex.Matches(source, @"--lx-[\w-]+\s*:\s*[^;{}]+;", RegexOptions.IgnoreCase)
            .Select(x => x.Value);
        var safeCss = $":root {{\n  {string.Join("\n  ", declarations)}\n}}";
        if (!safeCss.Contains("--lx-")) throw new InvalidDataException("没有找到 --lx-* CSS 变量。");
        _vm.State.ThemeName = "自定义";
        _vm.State.CustomThemePath = dialog.FileName;
        _vm.State.CustomThemeCss = safeCss;
        _vm.Persist();
    }

    private void MigrateLegacyCustomTheme()
    {
        if (!string.IsNullOrWhiteSpace(_vm.State.CustomThemeCss) || string.IsNullOrWhiteSpace(_vm.State.CustomThemePath) || !File.Exists(_vm.State.CustomThemePath)) return;
        try { _vm.State.CustomThemeCss = SanitizeTheme(File.ReadAllText(_vm.State.CustomThemePath)); _vm.Persist(); } catch { _vm.State.CustomThemePath = null; _vm.Persist(); }
    }

    private static string SanitizeTheme(string source)
    {
        if (Regex.IsMatch(source, @"url\s*\(|@import|expression\s*\(", RegexOptions.IgnoreCase)) throw new InvalidDataException("皮肤不能包含远程资源或可执行表达式。");
        var declarations = Regex.Matches(source, @"--lx-[\w-]+\s*:\s*[^;{}]+;", RegexOptions.IgnoreCase).Select(x => x.Value);
        var safeCss = $":root {{\n  {string.Join("\n  ", declarations)}\n}}";
        if (!safeCss.Contains("--lx-")) throw new InvalidDataException("没有找到 --lx-* CSS 变量。");
        return safeCss;
    }

    private void ExportTheme()
    {
        var dialog = new SaveFileDialog { Filter = "CSS 皮肤 (*.css)|*.css", FileName = "LedgerX-theme.css" };
        if (dialog.ShowDialog(this) != true) return;
        File.WriteAllText(dialog.FileName, _vm.State.CustomThemeCss ?? BuiltInThemeCss(_vm.State.ThemeName));
    }

    private object BuildSnapshot()
    {
        var state = _vm.State;
        var analysis = CalculateAnalysis(state.Records);
        return new
        {
            metrics = _vm.Metrics.Select(m => new
            {
                m.Id, m.Name, m.Subtitle, m.DisplayValue, rawValue = (decimal?)null,
                m.CanRecord, m.IsCustom, m.IsHidden, m.IsEnabled,
                displayFormat = m.DisplayFormat.ToString()
            }),
            records = _vm.Records.Select(r => new
            {
                id = r.Id.ToString(), date = r.Date.ToString("yyyy-MM-dd"),
                type = r.Type.ToString(), r.TypeLabel, r.Amount, r.DisplayAmount,
                r.Category, r.Account, r.Note, r.CustomMetricId
            }),
            state.HideAllAmounts, state.ThemeName, state.CustomThemeCss,
            dataFile = _store.DataFile,
            analysis
        };
    }

    private static object CalculateAnalysis(IEnumerable<FinanceRecord> source)
    {
        var records = source.ToList();
        var income = records.Where(x => x.Type == FinanceRecordType.Income).Sum(x => x.Amount);
        var fixedCost = records.Where(x => x.Type == FinanceRecordType.FixedCost).Sum(x => x.Amount);
        var variableCost = records.Where(x => x.Type == FinanceRecordType.VariableCost).Sum(x => x.Amount);
        var ownIncome = records.Where(x => x.Type == FinanceRecordType.Income && !x.Category.Contains("父母")).Sum(x => x.Amount);
        var dependency = income == 0 ? 0 : (income - ownIncome) / income * 100;
        var fixedRatio = fixedCost + variableCost == 0 ? 0 : fixedCost / (fixedCost + variableCost) * 100;
        var months = Math.Max(1, records.Select(x => (x.Date.Year, x.Date.Month)).Distinct().Count());
        var burn = (fixedCost + variableCost + records.Where(x => x.Type == FinanceRecordType.PayablePayment).Sum(x => x.Amount)) / months;
        var cash = income - fixedCost - variableCost
            - records.Where(x => x.Type == FinanceRecordType.FixedAssetPurchase).Sum(x => x.Amount)
            - records.Where(x => x.Type == FinanceRecordType.PayablePayment).Sum(x => x.Amount);
        var runway = burn > 0 ? Math.Max(0, cash) / burn : decimal.MaxValue;
        var verdict = records.Count == 0 ? "还没有记录。先录入一笔收入或成本，分析会立即出现。"
            : runway < 1 ? "资金链偏紧，建议先保留现金并检查可削减成本。"
            : runway < 3 ? "目前可维持时间有限，优先建立安全垫。"
            : "资金链暂时稳健，可以继续观察成本结构。";
        return new { empty = records.Count == 0, income, fixedCost, variableCost, dependency, fixedCostRatio = fixedRatio, verdict };
    }

    private void Respond(string id, bool ok, object? data, string? error)
    {
        if (WebView.CoreWebView2 is null) return;
        WebView.CoreWebView2.PostWebMessageAsJson(JsonSerializer.Serialize(new { id, ok, data, error }, _json));
    }

    private static string StringValue(JsonElement payload, string property, string fallback = "") =>
        payload.TryGetProperty(property, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() ?? fallback : fallback;
    private static string? NullableString(JsonElement payload, string property) =>
        payload.TryGetProperty(property, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static string BuiltInThemeCss(string name) => name switch
    {
        "石墨" => ":root { --lx-background:#F5F5F4; --lx-sidebar:#ECECEA; --lx-surface:#FFFFFF; --lx-primary:#444443; --lx-text:#181818; --lx-muted:#737373; --lx-border:#DADAD7; }",
        "深海蓝" => ":root { --lx-background:#F3F5F7; --lx-sidebar:#E9EDF1; --lx-surface:#FCFDFE; --lx-primary:#294A68; --lx-text:#18232D; --lx-muted:#687681; --lx-border:#D4DBE1; }",
        _ => ":root { --lx-background:#F7F6F3; --lx-sidebar:#F1F0ED; --lx-surface:#FFFDFC; --lx-primary:#9A6A3A; --lx-text:#1D1D1F; --lx-muted:#76736F; --lx-border:#DEDBD5; }"
    };
}
