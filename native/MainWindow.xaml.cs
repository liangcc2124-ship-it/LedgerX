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
    private readonly ProfileStore _profiles = new();
    private ProfileIndex _profileIndex;
    private LedgerStore _store;
    private readonly MetricEngine _metricEngine = new();
    private readonly ReportEngine _reportEngine;
    private readonly WarningEngine _warningEngine;
    private MainViewModel _vm;
    private string _selectedPeriod = "all";
    private DateTime? _customStart;
    private DateTime? _customEnd;
    private readonly JsonSerializerOptions _json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        Converters = { new JsonStringEnumConverter() }
    };

    public MainWindow()
    {
        InitializeComponent();
        _profileIndex = _profiles.Load();
        _store = new LedgerStore(_profiles.DataDirectory(_profileIndex, _profileIndex.ActiveProfileId));
        _reportEngine = new ReportEngine(_metricEngine);
        _warningEngine = new WarningEngine(_metricEngine);
        _vm = new MainViewModel(_store);
        _vm.State.ProfileId = _profileIndex.ActiveProfileId;
        _vm.Persist();
        Loaded += async (_, _) => await InitializeWebViewAsync();
    }

    private async Task InitializeWebViewAsync()
    {
        WriteWebViewDiagnostic("initialize-start");
        try
        {
            var userData = Path.Combine(_store.DataDirectory, "WebView2-v3");
            var environment = await CoreWebView2Environment.CreateAsync(null, userData);
            await WebView.EnsureCoreWebView2Async(environment);
            WebView.CoreWebView2.Settings.AreDevToolsEnabled = false;
            WebView.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
            WebView.CoreWebView2.Settings.IsStatusBarEnabled = false;
            WebView.CoreWebView2.WebMessageReceived += OnWebMessageReceived;
            WebView.CoreWebView2.ProcessFailed += (_, args) =>
            {
                WriteWebViewDiagnostic($"process-failed: {args.ProcessFailedKind}");
            };

            var executableDirectory = Path.GetDirectoryName(Environment.ProcessPath) ?? AppContext.BaseDirectory;
            var webRoot = Path.Combine(executableDirectory, "web");
            if (!Directory.Exists(webRoot))
                throw new DirectoryNotFoundException($"界面资源不存在：{webRoot}");

            WebView.CoreWebView2.SetVirtualHostNameToFolderMapping(
                "app.ledgerx.local", webRoot, CoreWebView2HostResourceAccessKind.DenyCors);
            WebView.CoreWebView2.NavigationCompleted += OnNavigationCompleted;
            WriteWebViewDiagnostic("navigation-starting: https://app.ledgerx.local/index.html");
            WebView.CoreWebView2.Navigate("https://app.ledgerx.local/index.html");
        }
        catch (Exception ex)
        {
            WriteWebViewDiagnostic($"initialize-failed: {ex}");
            var panel = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Width = 520 };
            panel.Children.Add(new TextBlock { Text = "LedgerX 界面加载失败", FontSize = 24, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 0, 12) });
            panel.Children.Add(new TextBlock { Text = $"{ex.Message}\n\n如果提示 WebView2 Runtime 缺失，请安装 Microsoft Edge WebView2 Runtime；如果资源缺失，可重新解压完整发布包。", TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 18) });
            var buttons = new StackPanel { Orientation = Orientation.Horizontal };
            var retry = new Button { Content = "重新加载", Padding = new Thickness(16, 8, 16, 8), Margin = new Thickness(0, 0, 10, 0) }; retry.Click += async (_, _) => await InitializeWebViewAsync(); buttons.Children.Add(retry);
            var folder = new Button { Content = "打开数据目录", Padding = new Thickness(16, 8, 16, 8), Margin = new Thickness(0, 0, 10, 0) }; folder.Click += (_, _) => System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("explorer.exe", _store.DataDirectory) { UseShellExecute = true }); buttons.Children.Add(folder);
            var runtime = new Button { Content = "WebView2 安装说明", Padding = new Thickness(16, 8, 16, 8) }; runtime.Click += (_, _) => System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("https://developer.microsoft.com/microsoft-edge/webview2/") { UseShellExecute = true }); buttons.Children.Add(runtime);
            panel.Children.Add(buttons); LoadingPanel.Child = panel;
        }
    }

    private void WriteWebViewDiagnostic(string message)
    {
        try
        {
            File.AppendAllText(Path.Combine(_store.DataDirectory, "webview.log"), $"[{DateTime.Now:O}] {message}{Environment.NewLine}");
        }
        catch
        {
            // Diagnostics must never prevent the UI from loading.
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
            var commandResult = await ExecuteCommandAsync(command, payload);
            Respond(id, true, commandResult ?? BuildSnapshot(), null);
        }
        catch (Exception ex)
        {
            WriteWebViewDiagnostic($"bridge-failed: {ex}");
            Respond(id, false, null, ex.Message);
        }
    }

    private async Task<object?> ExecuteCommandAsync(string command, JsonElement payload)
    {
        object? result = null;
        switch (command)
        {
case "getState": break;
            case "getWarningRules": result = _vm.State.WarningRules.Select(RuleDto); break;
            case "queryRecords":
            {
                var rows = _vm.State.Records.Where(x => x.DeletedAt is null);
                if (payload.TryGetProperty("from", out var from) && DateTime.TryParse(from.GetString(), out var fromDate)) rows = rows.Where(x => x.Date.Date >= fromDate.Date);
                if (payload.TryGetProperty("to", out var to) && DateTime.TryParse(to.GetString(), out var toDate)) rows = rows.Where(x => x.Date.Date <= toDate.Date);
                if (payload.TryGetProperty("type", out var type) && Enum.TryParse<FinanceRecordType>(type.GetString(), out var recordType)) rows = rows.Where(x => x.Type == recordType);
                result = rows.OrderByDescending(x => x.Date).Select(x => new { id = x.Id.ToString(), date = x.Date.ToString("yyyy-MM-dd"), type = x.Type.ToString(), x.Amount, x.Category, x.Account, x.Note, x.IncomeSource }); break;
            }
            case "previewBackup": result = _store.PreviewBackup(StringValue(payload,"path")); break;
            case "createBackup": _store.Backup(StringValue(payload,"path"), _vm.State); break;
            case "restoreBackup":
            {
                var path=StringValue(payload,"path");_store.CreateProtectionSnapshot("before-restore",_vm.State);var restored=_store.Restore(path);_store.CompleteRecovery();_vm.ReplaceState(restored);break;
            }
            case "getDashboard": _selectedPeriod=StringValue(payload,"period","all");_customStart=ParseDate(payload,"start");_customEnd=ParseDate(payload,"end");result=BuildSnapshot();break;
            case "getMetricDetail": result = BuildMetricDetail(payload); break;
            case "getReport": result = _reportEngine.Build(_vm.State,StringValue(payload,"kind","month"),ParseDate(payload,"anchor")); break;
            case "getSettings": result = BuildSettings(); break;
            case "getDiagnostics": result = BuildDiagnostics(); break;
            case "previewWarningRule": result = _warningEngine.Preview(WarningRuleFromPayload(payload),_vm.State); break;
            case "saveWarningRule":
            {
                var candidate=WarningRuleFromPayload(payload);var existing=_vm.State.WarningRules.FirstOrDefault(x=>x.Id==candidate.Id);
                if(existing is null)_vm.State.WarningRules.Add(candidate);else CopyRule(candidate,existing);
                _vm.Persist();break;
            }
            case "toggleWarningRule": { var rule=FindWarningRule(payload);rule.Enabled=payload.GetProperty("enabled").GetBoolean();rule.UpdatedAt=DateTime.Now;_vm.Persist();break; }
            case "copyWarningRule": { var source=FindWarningRule(payload);var copy=CloneRule(source);copy.Id=Guid.NewGuid();copy.Name+="（副本）";copy.CreatedAt=copy.UpdatedAt=DateTime.Now;_vm.State.WarningRules.Add(copy);_vm.Persist();break; }
            case "deleteWarningRule": { var id=Guid.Parse(payload.GetProperty("id").GetString()!);_vm.State.WarningRules.RemoveAll(x=>x.Id==id);_vm.State.WarningEvents.RemoveAll(x=>x.RuleId==id);_vm.Persist();break; }
            case "updateWarningEvent": { var evt=_vm.State.WarningEvents.FirstOrDefault(x=>x.Id==Guid.Parse(payload.GetProperty("id").GetString()!))??throw new InvalidOperationException("找不到预警事件。");evt.Status=StringValue(payload,"status",evt.Status);evt.SnoozedUntil=evt.Status=="snoozed"?DateTime.Now.AddHours(24):null;_vm.Persist();break; }
            case "bulkUpdateIncomeSource":
            {
                var ids=payload.GetProperty("ids").EnumerateArray().Select(x=>Guid.Parse(x.GetString()!)).ToHashSet();var source=StringValue(payload,"incomeSource","其他");var self=payload.TryGetProperty("isSelfGeneratedIncome",out var generated)&&generated.GetBoolean();
                foreach(var record in _vm.State.Records.Where(x=>ids.Contains(x.Id)&&x.DeletedAt is null&&x.Type==FinanceRecordType.Income)){record.IncomeSource=source;record.IsSelfGeneratedIncome=self;record.UpdatedAt=DateTime.Now;}_vm.Persist();break;
            }            case "createProfile":
            {
                var profile = new ProfileDefinition { Name = StringValue(payload, "name", "新用户空间") };
                _profileIndex.Profiles.Add(profile); _profileIndex.ActiveProfileId = profile.Id; _profiles.Save(_profileIndex);
                _store = new LedgerStore(_profiles.DataDirectory(_profileIndex, profile.Id)); _vm = new MainViewModel(_store); _vm.State.ProfileId = profile.Id; _vm.Persist(); break;
            }
            case "switchProfile":
            {
                var id = Guid.Parse(StringValue(payload, "id")); var profile = _profileIndex.Profiles.FirstOrDefault(x => x.Id == id && !x.IsArchived) ?? throw new InvalidOperationException("用户空间不存在或已归档。");
                profile.LastOpenedAt = DateTime.Now; _profileIndex.ActiveProfileId = id; _profiles.Save(_profileIndex);
                _store = new LedgerStore(_profiles.DataDirectory(_profileIndex, id)); _vm = new MainViewModel(_store); _vm.State.ProfileId = id; _vm.Persist(); break;
            }
            case "archiveProfile":
            {
                var id = Guid.Parse(StringValue(payload, "id")); if (id == _profileIndex.ActiveProfileId) throw new InvalidOperationException("不能归档当前用户空间。");
                var profile = _profileIndex.Profiles.FirstOrDefault(x => x.Id == id) ?? throw new InvalidOperationException("用户空间不存在。"); profile.IsArchived = true; _profiles.Save(_profileIndex); break;
            }
            case "saveCategory":
            {
                var id = Guid.TryParse(NullableString(payload, "id"), out var parsed) ? parsed : Guid.NewGuid();
                var item = _vm.State.Categories.FirstOrDefault(x => x.Id == id);
                if (item is null) { item = new CategoryDefinition { Id = id, SortOrder = _vm.State.Categories.Count }; _vm.State.Categories.Add(item); }
                item.Name = StringValue(payload, "name"); item.ParentId = Guid.TryParse(NullableString(payload, "parentId"), out var parent) ? parent : null; _vm.Persist(); break;
            }
            case "archiveCategories":
            {
                var ids = GuidIds(payload); foreach (var item in _vm.State.Categories.Where(x => ids.Contains(x.Id) && !x.IsSystem)) item.IsArchived = true; _vm.Persist(); break;
            }
            case "mergeCategories":
            {
                var ids = GuidIds(payload); var target = Guid.Parse(StringValue(payload, "targetId")); var category = _vm.State.Categories.FirstOrDefault(x => x.Id == target) ?? throw new InvalidOperationException("目标分类不存在。");
                foreach (var record in _vm.State.Records.Where(x => x.CategoryId is Guid id && ids.Contains(id))) { record.CategoryId = target; record.Category = category.Name; } _vm.Persist(); break;
            }
            case "saveAccount":
            {
                var id = Guid.TryParse(NullableString(payload, "id"), out var parsed) ? parsed : Guid.NewGuid(); var account = _vm.State.FinancialAccounts.FirstOrDefault(x => x.Id == id);
                if (account is null) { account = new FinancialAccount { Id = id }; _vm.State.FinancialAccounts.Add(account); }
                account.Name = StringValue(payload, "name"); account.Kind = Enum.TryParse<FinancialAccountKind>(StringValue(payload, "kind", "Cash"), out var kind) ? kind : FinancialAccountKind.Cash;
                account.BalanceSide = account.Kind is FinancialAccountKind.Credit or FinancialAccountKind.Loan or FinancialAccountKind.OtherLiability ? BalanceSide.Liability : BalanceSide.Asset;
                account.OpeningBalance = payload.TryGetProperty("openingBalance", out var balance) ? balance.GetDecimal() : account.OpeningBalance; account.OpeningDate = ParseDate(payload, "openingDate") ?? DateTime.Today;
                account.IncludeInAvailableCash = payload.TryGetProperty("includeInAvailableCash", out var include) && include.GetBoolean(); _vm.Persist(); break;
            }
            case "archiveAccounts":
            {
                var ids = GuidIds(payload); foreach (var account in _vm.State.FinancialAccounts.Where(x => ids.Contains(x.Id) && !x.IsSystem)) account.IsArchived = true; _vm.Persist(); break;
            }
            case "bulkDeleteRecords": foreach (var id in GuidIds(payload)) { var record = _vm.State.Records.FirstOrDefault(x => x.Id == id && x.DeletedAt is null); if (record is not null) _vm.DeleteRecord(record); } break;
            case "bulkRestoreRecords": foreach (var id in GuidIds(payload)) _vm.RestoreRecord(id); break;
            case "bulkPurgeRecords": foreach (var id in GuidIds(payload)) _vm.PurgeRecord(id); break;
            case "bulkUpdateRecords":
            {
                var ids = GuidIds(payload); foreach (var record in _vm.State.Records.Where(x => ids.Contains(x.Id) && x.DeletedAt is null)) { if (payload.TryGetProperty("patch", out var patch) && patch.TryGetProperty("categoryId", out var categoryId) && Guid.TryParse(categoryId.GetString(), out var parsedCategory)) record.CategoryId = parsedCategory; record.UpdatedAt = DateTime.Now; } _vm.Persist(); break;
            }
            case "validateFormula": result = ValidateFormula(payload); break;
            case "previewFormula": result = PreviewFormula(payload); break;
            case "saveAllocationPlan": SaveAllocationPlan(payload); break;
            case "previewAllocation": result = PreviewAllocation(payload); break;
            case "saveFixedAsset": SaveFixedAsset(payload); break;
            case "addMetricFromTemplate": AddMetricFromTemplate(payload); break;
            case "saveMetricDefinition": _vm.AddCustomMetric(new CustomMetricDefinition { Name = StringValue(payload, "name"), Subtitle = StringValue(payload, "subtitle", "自定义指标"), DisplayFormat = Enum.TryParse<MetricDisplayFormat>(StringValue(payload, "displayFormat", "Currency"), out var format) ? format : MetricDisplayFormat.Currency }); break;
            case "saveFormula": result = SaveFormula(payload); break;
            case "archiveMetrics": foreach (var id in StringIds(payload)) _vm.State.EnabledMetrics.Remove(id); _vm.Persist(); break;
            case "deleteMetrics": foreach (var id in StringIds(payload)) { var metric = _vm.Metrics.FirstOrDefault(x => x.Id == id); if (metric is not null && metric.IsCustom) _vm.DeleteCustomMetric(metric); } break;
            case "previewDepreciation": result = PreviewDepreciation(payload); break;
            case "disposeFixedAsset": { var asset = _vm.State.FixedAssets.FirstOrDefault(x => x.Id == Guid.Parse(StringValue(payload, "id"))) ?? throw new InvalidOperationException("固定资产不存在。"); asset.Status = FixedAssetStatus.Disposed; asset.DisposedAt = DateTime.Today; _vm.Persist(); break; }
            case "saveDashboardLayout": SaveDashboardLayout(payload); break;
            case "resetDashboardLayout": _vm.State.DashboardLayouts.Clear(); _vm.Persist(); break;
            case "addRecord": { var record = RecordFromPayload(payload); _vm.AddRecord(record); if (payload.TryGetProperty("fixedAsset", out var assetPayload) && assetPayload.ValueKind == JsonValueKind.Object) CreateFixedAsset(record, assetPayload); break; }
            case "updateRecord": _vm.UpdateRecord(Guid.Parse(payload.GetProperty("id").GetString()!),RecordFromPayload(payload)); break;
            case "deleteRecord": { var id=Guid.Parse(payload.GetProperty("id").GetString()!);var record=_vm.State.Records.FirstOrDefault(x=>x.Id==id);if(record is not null)_vm.DeleteRecord(record);break; }
            case "restoreRecord": _vm.RestoreRecord(Guid.Parse(payload.GetProperty("id").GetString()!)); break;
            case "purgeRecord": _vm.PurgeRecord(Guid.Parse(payload.GetProperty("id").GetString()!)); break;
            case "clearLedger":
                if(StringValue(payload,"confirmation")!="清空 LedgerX")throw new InvalidOperationException("请输入“清空 LedgerX”确认。");
                _store.CreateProtectionSnapshot("before-clear",_vm.State);_vm.State.Records.Clear();_vm.State.CustomMetrics.Clear();_vm.State.WarningRules.Clear();_vm.State.WarningEvents.Clear();_vm.ReplaceState(_vm.State);break;
            case "factoryReset":
                if(StringValue(payload,"confirmation")!="恢复出厂")throw new InvalidOperationException("请输入“恢复出厂”确认。");
                _store.CreateProtectionSnapshot("before-factory-reset",_vm.State);var fresh=new LedgerState();_vm.ReplaceState(fresh);break;
            case "undoLastDanger":
            {
                var path=StringValue(payload,"path",_store.LastProtectionSnapshot??string.Empty);if(string.IsNullOrWhiteSpace(path)||!File.Exists(path))throw new InvalidOperationException("没有可撤销的保护快照。");
                var restored=_store.RestoreProtectionSnapshot(path);_store.CompleteRecovery();_vm.ReplaceState(restored);break;
            }
            case "createEmptyAfterRecovery": _store.CompleteRecoveryWithEmptyLedger();_vm.ReplaceState(_store.Load());break;
            case "toggleAllPrivacy": _vm.ToggleAllPrivacy(); break;
            case "toggleMetricPrivacy": _vm.ToggleMetricPrivacy(FindMetric(payload)); break;
            case "setMetricEnabled": _vm.SetMetricEnabled(FindMetric(payload),payload.GetProperty("enabled").GetBoolean()); break;
            case "addCustomMetric": _vm.AddCustomMetric(new CustomMetricDefinition{Name=StringValue(payload,"name"),Subtitle=StringValue(payload,"subtitle","自定义指标"),DisplayFormat=Enum.Parse<MetricDisplayFormat>(StringValue(payload,"displayFormat","Currency"))});break;
            case "deleteCustomMetric": _vm.DeleteCustomMetric(FindMetric(payload));break;
            case "setTheme": _vm.State.ThemeName=StringValue(payload,"name","暖铜");_vm.State.CustomThemeCss=null;_vm.State.CustomThemePath=null;_vm.Persist();break;
            case "saveSettings": ApplySettings(payload);_vm.Persist();break;
            case "backup": Backup();break;
            case "restore": Restore();break;
            case "importTheme": ImportTheme();break;
            case "exportTheme": ExportTheme();break;
            case "openDataFolder": System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("explorer.exe",_store.DataDirectory){UseShellExecute=true});break;
            case "exportReportPdf": await ExportReportPdfAsync();break;
            default: throw new InvalidOperationException($"未知操作：{command}");
        }
        return result;
    }
    private static HashSet<Guid> GuidIds(JsonElement payload) => payload.TryGetProperty("ids", out var ids) && ids.ValueKind == JsonValueKind.Array
        ? ids.EnumerateArray().Select(x => Guid.Parse(x.GetString()!)).ToHashSet() : [];
    private static IReadOnlyList<string> StringIds(JsonElement payload) => payload.TryGetProperty("ids", out var ids) && ids.ValueKind == JsonValueKind.Array
        ? ids.EnumerateArray().Select(x => x.GetString()!).ToList() : [];

    private object ValidateFormula(JsonElement payload)
    {
        var formula = FormulaFromPayload(payload);
        var validation = new FormulaEngine().Validate(formula, _vm.State.MetricDefinitions, _vm.State.FormulaDefinitions);
        return new { valid = validation.IsValid, errors = validation.Errors, dependencies = validation.Dependencies };
    }

    private object PreviewFormula(JsonElement payload)
    {
        var validation = ValidateFormula(payload);
        return new { value = 0m, status = "公式已通过结构校验；保存后会随账本数据计算。", validation };
    }

    private void SaveAllocationPlan(JsonElement payload)
    {
        var plan = AllocationFromPayload(payload); var existing = _vm.State.AllocationPlans.FirstOrDefault(x => x.Id == plan.Id);
        if (existing is null) _vm.State.AllocationPlans.Add(plan); else { existing.Amount = plan.Amount; existing.ServiceStart = plan.ServiceStart; existing.ServiceEndExclusive = plan.ServiceEndExclusive; existing.RecognitionMethod = plan.RecognitionMethod; existing.FormulaId = plan.FormulaId; existing.UpdatedAt = DateTime.Now; }
        _vm.Persist();
    }

    private object PreviewAllocation(JsonElement payload)
    {
        var plan = AllocationFromPayload(payload); var formula = plan.FormulaId is Guid id ? _vm.State.FormulaDefinitions.FirstOrDefault(x => x.Id == id) : null;
        var schedule = new AllocationEngine().BuildSchedule(plan, StringValue(payload, "unit", "month"), formula).Select(x => new { start = x.Start.ToString("yyyy-MM-dd"), endExclusive = x.EndExclusive.ToString("yyyy-MM-dd"), x.Amount });
        return new { total = plan.Amount, method = plan.RecognitionMethod.ToString(), periods = schedule };
    }

    private void SaveFixedAsset(JsonElement payload)
    {
        var id = Guid.TryParse(NullableString(payload, "id"), out var parsed) ? parsed : Guid.NewGuid(); var asset = _vm.State.FixedAssets.FirstOrDefault(x => x.Id == id);
        if (asset is null) { asset = new FixedAsset { Id = id }; _vm.State.FixedAssets.Add(asset); }
        asset.Name = StringValue(payload, "name", "未命名资产"); asset.CategoryId = Guid.TryParse(NullableString(payload, "categoryId"), out var categoryId) ? categoryId : null;
        asset.InServiceDate = ParseDate(payload, "inServiceDate") ?? DateTime.Today; asset.Cost = payload.TryGetProperty("cost", out var cost) ? cost.GetDecimal() : asset.Cost;
        asset.SalvageValue = payload.TryGetProperty("residualValue", out var residual) ? residual.GetDecimal() : asset.SalvageValue; asset.UsefulLifeMonths = payload.TryGetProperty("usefulLifeMonths", out var life) ? Math.Max(1, life.GetInt32()) : Math.Max(1, asset.UsefulLifeMonths);
        asset.Method = StringValue(payload, "depreciationMethod", "StraightLine") switch { "UnitsOfProduction" => DepreciationMethod.UnitsOfProduction, "DoubleDeclining" => DepreciationMethod.DoubleDecliningBalance, "SumOfYears" => DepreciationMethod.SumOfYearsDigits, "Custom" => DepreciationMethod.CustomFormula, _ => DepreciationMethod.StraightLine };
        asset.Status = FixedAssetStatus.Active; _vm.Persist();
    }

    private static AllocationPlan AllocationFromPayload(JsonElement payload) => new()
    {
        Id = Guid.TryParse(NullableString(payload, "id"), out var id) ? id : Guid.NewGuid(), RecordId = Guid.TryParse(NullableString(payload, "recordId"), out var recordId) ? recordId : Guid.Empty,
        Amount = payload.TryGetProperty("amount", out var amount) ? amount.GetDecimal() : 0m, ServiceStart = ParseDate(payload, "serviceStart") ?? DateTime.Today,
        ServiceEndExclusive = ParseDate(payload, "serviceEndExclusive") ?? DateTime.Today.AddMonths(1), RecognitionMethod = RecognitionFromPayload(payload), FormulaId = Guid.TryParse(NullableString(payload, "formulaId"), out var formulaId) ? formulaId : null
    };

    private static FormulaDefinition FormulaFromPayload(JsonElement payload)
    {
        var scope = Enum.TryParse<FormulaScope>(StringValue(payload, "scope", "Metric"), out var parsedScope) ? parsedScope : FormulaScope.Metric;
        var tokens = payload.TryGetProperty("tokens", out var input) && input.ValueKind == JsonValueKind.Array
            ? input.EnumerateArray().Select(item => new FormulaToken
            {
                Id = Guid.TryParse(NullableString(item, "id"), out var id) ? id : Guid.NewGuid(),
                Kind = StringValue(item, "kind", "source").ToLowerInvariant() switch { "operator" or "group" => FormulaTokenKind.Operator, "function" => FormulaTokenKind.Function, "constant" => FormulaTokenKind.Constant, _ => FormulaTokenKind.Source },
                Value = StringValue(item, "value"), Label = NullableString(item, "label")
            }).ToList()
            : [];
        var expression = new FormulaEngine().ParseTokens(tokens, scope);
        return new FormulaDefinition
        {
            Scope = scope,
            ResultType = Enum.TryParse<FormulaResultType>(StringValue(payload, "resultType", "Money"), out var result) ? result : FormulaResultType.Money,
            Tokens = tokens,
            RootExpression = expression
        };
    }
    private static IEnumerable<object> FormulaTokens(FormulaExpression expression)
    {
        IEnumerable<object> Visit(FormulaExpression node)
        {
            if (node.NodeType == FormulaNodeType.Constant) return [new { id = Guid.NewGuid().ToString(), kind = "constant", label = node.Constant?.ToString(System.Globalization.CultureInfo.InvariantCulture) ?? "0", value = node.Constant?.ToString(System.Globalization.CultureInfo.InvariantCulture) ?? "0" }];
            if (node.NodeType == FormulaNodeType.Variable) return [new { id = Guid.NewGuid().ToString(), kind = "source", label = node.Variable ?? string.Empty, value = node.Variable ?? string.Empty }];
            var symbol = node.NodeType switch { FormulaNodeType.Add => "+", FormulaNodeType.Subtract => "−", FormulaNodeType.Multiply => "×", FormulaNodeType.Divide => "÷", _ => string.Empty };
            if (!string.IsNullOrEmpty(symbol) && node.Arguments.Count == 2) return Visit(node.Arguments[0]).Concat([new { id = Guid.NewGuid().ToString(), kind = "operator", label = symbol, value = symbol }]).Concat(Visit(node.Arguments[1]));
            return [new { id = Guid.NewGuid().ToString(), kind = "source", label = "记录金额合计", value = "记录金额合计" }];
        }
        return Visit(expression);
    }
    private void AddMetricFromTemplate(JsonElement payload)
    {
        var template = MetricTemplateCatalog.All.FirstOrDefault(x => x.Id == StringValue(payload, "templateId")) ?? throw new InvalidOperationException("指标模板不存在。");
        var id = $"custom-{Guid.NewGuid():N}";
        var formula = new FormulaDefinition { Scope = FormulaScope.Metric, RootExpression = template.Formula, Dependencies = [] };
        _vm.State.FormulaDefinitions.Add(formula);
        _vm.State.MetricDefinitions.Add(new MetricDefinition { Id = id, Name = StringValue(payload, "name", template.Name), Description = template.Description, DisplayFormat = template.DisplayFormat, FormulaId = formula.Id, TemplateId = template.Id, AcceptsDirectRecordAssignment = template.AcceptsDirectRecordAssignment });
        _vm.AddCustomMetric(new CustomMetricDefinition { Id = id, Name = StringValue(payload, "name", template.Name), Subtitle = template.Description, DisplayFormat = template.DisplayFormat });
    }

    private object SaveFormula(JsonElement payload)
    {
        var candidate = FormulaFromPayload(payload); var validation = new FormulaEngine().Validate(candidate);
        if (!validation.IsValid) throw new BridgeException("formula_invalid", "结构化公式无效。", new Dictionary<string, string> { ["tokens"] = string.Join("；", validation.Errors) });
        var id = Guid.TryParse(NullableString(payload, "id"), out var parsed) ? parsed : Guid.NewGuid();
        var formula = _vm.State.FormulaDefinitions.FirstOrDefault(x => x.Id == id);
        if (formula is null) { formula = new FormulaDefinition { Id = id }; _vm.State.FormulaDefinitions.Add(formula); }
        formula.Scope = candidate.Scope; formula.ResultType = candidate.ResultType; formula.Tokens = candidate.Tokens; formula.RootExpression = candidate.RootExpression; formula.Dependencies = validation.Dependencies.ToList(); formula.Version++;
        _vm.Persist(); return new { id = formula.Id.ToString() };
    }

    private void CreateFixedAsset(FinanceRecord record, JsonElement payload)
    {
        var asset = new FixedAsset
        {
            Name = StringValue(payload, "name", "未命名资产"), CategoryId = Guid.TryParse(NullableString(payload, "categoryId"), out var categoryId) ? categoryId : null,
            AcquisitionRecordId = record.Id, InServiceDate = ParseDate(payload, "inServiceDate") ?? record.Date, Cost = payload.TryGetProperty("cost", out var cost) ? cost.GetDecimal() : record.Amount,
            SalvageValue = payload.TryGetProperty("residualValue", out var residual) ? residual.GetDecimal() : 0m,
            UsefulLifeMonths = payload.TryGetProperty("usefulLifeMonths", out var life) ? Math.Max(1, life.GetInt32()) : 36,
            Method = StringValue(payload, "depreciationMethod", "StraightLine") switch { "DoubleDeclining" => DepreciationMethod.DoubleDecliningBalance, "SumOfYears" => DepreciationMethod.SumOfYearsDigits, "UnitsOfProduction" => DepreciationMethod.UnitsOfProduction, "Custom" => DepreciationMethod.CustomFormula, _ => DepreciationMethod.StraightLine },
            Status = FixedAssetStatus.Active
        };
        _vm.State.FixedAssets.Add(asset); record.FixedAssetId = asset.Id; _vm.Persist();
    }
    private static object PreviewDepreciation(JsonElement payload)
    {
        var cost = payload.TryGetProperty("cost", out var costValue) ? costValue.GetDecimal() : 0m;
        var residual = payload.TryGetProperty("residualValue", out var residualValue) ? residualValue.GetDecimal() : 0m;
        var months = payload.TryGetProperty("usefulLifeMonths", out var monthsValue) ? Math.Max(1, monthsValue.GetInt32()) : 1;
        return new { monthlyAmount = Math.Max(0, (cost - residual) / months), bookValue = cost };
    }

    private void SaveDashboardLayout(JsonElement payload)
    {
        var breakpoint = StringValue(payload, "breakpoint", "desktop");
        var layout = _vm.State.DashboardLayouts.FirstOrDefault(x => x.Breakpoint == breakpoint) ?? new DashboardLayout { ProfileId = _profileIndex.ActiveProfileId, Breakpoint = breakpoint };
        if (!_vm.State.DashboardLayouts.Contains(layout)) _vm.State.DashboardLayouts.Add(layout);
        layout.Items.Clear();
        if (payload.TryGetProperty("items", out var items) && items.ValueKind == JsonValueKind.Array)
            foreach (var item in items.EnumerateArray()) layout.Items.Add(new DashboardLayoutItem { WidgetId = StringValue(item, "widgetId"), X = item.TryGetProperty("x", out var x) ? x.GetInt32() : 0, Y = item.TryGetProperty("y", out var y) ? y.GetInt32() : 0, W = item.TryGetProperty("w", out var w) ? w.GetInt32() : 3, H = item.TryGetProperty("h", out var h) ? h.GetInt32() : 2, MinW = item.TryGetProperty("minW", out var minW) ? minW.GetInt32() : 2, MinH = item.TryGetProperty("minH", out var minH) ? minH.GetInt32() : 1, MaxW = item.TryGetProperty("maxW", out var maxW) ? maxW.GetInt32() : 12, MaxH = item.TryGetProperty("maxH", out var maxH) ? maxH.GetInt32() : 8 });
        layout.UpdatedAt = DateTime.Now; _vm.Persist();
    }
    private object BuildSettings() => new { settings=_vm.State.Settings,themeName=_vm.State.ThemeName,customThemeCss=_vm.State.CustomThemeCss??string.Empty,dataFile=_store.DataFile,dataDirectory=_store.DataDirectory,dataFileSize=File.Exists(_store.DataFile)?new FileInfo(_store.DataFile).Length:0,recordCount=_vm.State.Records.Count(x=>x.DeletedAt is null),deletedRecordCount=_vm.State.Records.Count(x=>x.DeletedAt is not null),customMetricCount=_vm.State.CustomMetrics.Count,warningRuleCount=_vm.State.WarningRules.Count,schemaVersion=_vm.State.SchemaVersion,backupFormatVersion=2,appVersion="3.0.0",lastSavedAt=_vm.State.UpdatedAt,lastBackupAt=_vm.State.LastBackupAt,lastBackupPath=_store.LastBackupPath,dataIntegrityStatus=_store.RecoveryRequired?"需要恢复":"正常",snapshots=_store.GetAvailableSnapshots() };
    private object BuildDiagnostics() => new { status=_store.RecoveryRequired?"需要恢复":"正常",recoveryMessage=_store.RecoveryMessage,damagedFile=_store.DamagedFile,webViewVersion=CoreWebView2Environment.GetAvailableBrowserVersionString(),logEntries=Array.Empty<string>() };
    private WarningRule FindWarningRule(JsonElement payload)=>_vm.State.WarningRules.FirstOrDefault(x=>x.Id==Guid.Parse(payload.GetProperty("id").GetString()!))??throw new InvalidOperationException("找不到预警规则。");
    private WarningRule WarningRuleFromPayload(JsonElement payload)
    {
        var rule=new WarningRule{Id=payload.TryGetProperty("id",out var id)&&Guid.TryParse(id.GetString(),out var parsed)?parsed:Guid.NewGuid(),Name=StringValue(payload,"name","未命名规则"),Description=StringValue(payload,"description"),MetricId=StringValue(payload,"metricId","cash"),Operator=StringValue(payload,"operator","<"),Threshold=payload.TryGetProperty("threshold",out var threshold)?threshold.GetDecimal():0,Unit=StringValue(payload,"unit","元"),Period=StringValue(payload,"period","month"),ComparisonMode=StringValue(payload,"comparisonMode","threshold"),ComparisonWindow=payload.TryGetProperty("comparisonWindow",out var window)?window.GetInt32():4,ConsecutivePeriods=payload.TryGetProperty("consecutivePeriods",out var consecutive)?consecutive.GetInt32():1,Severity=StringValue(payload,"severity","关注"),RepeatPolicy=StringValue(payload,"repeatPolicy","cooldown"),CooldownHours=payload.TryGetProperty("cooldownHours",out var cooldown)?cooldown.GetInt32():24,EffectiveFrom=ParseDate(payload,"effectiveFrom"),EffectiveTo=ParseDate(payload,"effectiveTo"),NotificationMethod=StringValue(payload,"notificationMethod","inApp"),ShowAmountInNotification=payload.TryGetProperty("showAmountInNotification",out var show)&&show.GetBoolean(),Enabled=!payload.TryGetProperty("enabled",out var enabled)||enabled.GetBoolean()};
        if(payload.TryGetProperty("conditions",out var conditions)&&conditions.ValueKind==JsonValueKind.Array)foreach(var c in conditions.EnumerateArray())rule.Conditions.Add(new WarningCondition{MetricId=StringValue(c,"metricId","cash"),Operator=StringValue(c,"operator","<"),Threshold=c.TryGetProperty("threshold",out var t)?t.GetDecimal():0,ComparisonMode=StringValue(c,"comparisonMode","threshold"),ComparisonWindow=c.TryGetProperty("comparisonWindow",out var w)?w.GetInt32():4});return rule;
    }
    private static WarningRule CloneRule(WarningRule source)=>new(){Name=source.Name,Description=source.Description,Enabled=source.Enabled,MetricId=source.MetricId,Operator=source.Operator,Threshold=source.Threshold,Unit=source.Unit,Period=source.Period,ComparisonMode=source.ComparisonMode,ComparisonWindow=source.ComparisonWindow,ConsecutivePeriods=source.ConsecutivePeriods,Severity=source.Severity,RepeatPolicy=source.RepeatPolicy,CooldownHours=source.CooldownHours,EffectiveFrom=source.EffectiveFrom,EffectiveTo=source.EffectiveTo,NotificationMethod=source.NotificationMethod,ShowAmountInNotification=source.ShowAmountInNotification,Conditions=source.Conditions.Select(c=>new WarningCondition{MetricId=c.MetricId,Operator=c.Operator,Threshold=c.Threshold,ComparisonMode=c.ComparisonMode,ComparisonWindow=c.ComparisonWindow}).ToList()};
    private static void CopyRule(WarningRule source,WarningRule target){target.Name=source.Name;target.Description=source.Description;target.Enabled=source.Enabled;target.MetricId=source.MetricId;target.Operator=source.Operator;target.Threshold=source.Threshold;target.Unit=source.Unit;target.Period=source.Period;target.ComparisonMode=source.ComparisonMode;target.ComparisonWindow=source.ComparisonWindow;target.ConsecutivePeriods=source.ConsecutivePeriods;target.Severity=source.Severity;target.RepeatPolicy=source.RepeatPolicy;target.CooldownHours=source.CooldownHours;target.EffectiveFrom=source.EffectiveFrom;target.EffectiveTo=source.EffectiveTo;target.NotificationMethod=source.NotificationMethod;target.ShowAmountInNotification=source.ShowAmountInNotification;target.Conditions=source.Conditions;target.UpdatedAt=DateTime.Now;}
    private object RuleDto(WarningRule x)=>new{id=x.Id.ToString(),x.Name,x.Description,x.Enabled,x.MetricId,x.Operator,x.Threshold,x.Unit,x.Period,x.ComparisonMode,x.ComparisonWindow,x.ConsecutivePeriods,x.Severity,x.RepeatPolicy,x.CooldownHours,effectiveFrom=x.EffectiveFrom?.ToString("yyyy-MM-dd"),effectiveTo=x.EffectiveTo?.ToString("yyyy-MM-dd"),x.NotificationMethod,x.ShowAmountInNotification,conditions=x.Conditions};
    private object EventDto(WarningEvent x)=>new{id=x.Id.ToString(),ruleId=x.RuleId.ToString(),x.Status,x.MeasuredValue,x.Threshold,periodStart=x.PeriodStart.ToString("yyyy-MM-dd"),periodEnd=x.PeriodEnd.ToString("yyyy-MM-dd"),triggeredAt=x.TriggeredAt.ToString("O"),lastEvaluatedAt=x.LastEvaluatedAt.ToString("O"),resolvedAt=x.ResolvedAt?.ToString("O"),snoozedUntil=x.SnoozedUntil?.ToString("O"),evidenceRecordIds=x.EvidenceRecordIds.Select(id=>id.ToString()),x.Explanation};
    private static string FormatMetric(MetricItem metric,MetricValue? value,bool hidden){if(hidden)return "••••••";if(value?.Value is null)return value?.Status=="no-burn"?"暂无消耗":value?.Status=="insufficient"?"样本不足":"暂无法计算";return metric.DisplayFormat switch{MetricDisplayFormat.Percent=>$"{value.Value:N1}%",MetricDisplayFormat.Number when metric.Id is "runway" or "chain"=>$"{value.Value:N1} 个月",MetricDisplayFormat.Number=>$"{value.Value:N2}",_=>$"¥ {value.Value:N2}"};}
    private void ApplySettings(JsonElement payload){var settings=_vm.State.Settings;if(payload.TryGetProperty("notificationsEnabled",out var n))settings.NotificationsEnabled=n.GetBoolean();if(payload.TryGetProperty("autoBackupEnabled",out var a))settings.AutoBackupEnabled=a.GetBoolean();if(payload.TryGetProperty("autoBackupIntervalDays",out var i))settings.AutoBackupIntervalDays=Math.Clamp(i.GetInt32(),1,365);if(payload.TryGetProperty("autoBackupRetentionCount",out var r))settings.AutoBackupRetentionCount=Math.Clamp(r.GetInt32(),1,100);if(payload.TryGetProperty("safetyBufferTarget",out var target))settings.SafetyBufferTarget=Math.Max(0,target.GetDecimal());if(payload.TryGetProperty("lastSettingsSection",out var section))settings.LastSettingsSection=section.GetString()??"general";}
    private async Task ExportReportPdfAsync(){var dialog=new SaveFileDialog{Filter="PDF 文档 (*.pdf)|*.pdf",FileName=$"LedgerX-report-{DateTime.Today:yyyyMMdd}.pdf"};if(dialog.ShowDialog(this)==true&&!await WebView.CoreWebView2.PrintToPdfAsync(dialog.FileName))throw new InvalidOperationException("PDF 导出失败。");}
    private static FinanceRecord RecordFromPayload(JsonElement payload) => new()
    {
        Date = DateTime.Parse(payload.GetProperty("date").GetString()!),
        Type = Enum.Parse<FinanceRecordType>(payload.GetProperty("type").GetString()!),
        Amount = payload.GetProperty("amount").GetDecimal(),
        Category = StringValue(payload, "category"),
        Account = StringValue(payload, "account", "现金储备"),
        CategoryId = Guid.TryParse(NullableString(payload, "categoryId"), out var categoryId) ? categoryId : null,
        MetricTargetIds = payload.TryGetProperty("metricTargetIds", out var targets) && targets.ValueKind == JsonValueKind.Array ? targets.EnumerateArray().Select(x => x.GetString()!).ToList() : [],
        SettlementMode = SettlementFromPayload(payload),
        SettlementDate = ParseDate(payload, "settlementDate"),
        FinancialAccountId = Guid.TryParse(NullableString(payload, "financialAccountId"), out var accountId) ? accountId : null,
        ServiceStart = ParseDate(payload, "serviceStart"),
        ServiceEndExclusive = ParseDate(payload, "serviceEndExclusive"),
        BillingCadence = BillingFromPayload(payload),
        RecognitionMethod = RecognitionFromPayload(payload),
        AllocationFormulaId = Guid.TryParse(NullableString(payload, "allocationFormulaId"), out var formulaId) ? formulaId : null,
        Note = StringValue(payload, "note"),
        CustomMetricId = NullableString(payload, "customMetricId"),
        IncomeSource = NullableString(payload, "incomeSource"),
        IsSelfGeneratedIncome = payload.TryGetProperty("isSelfGeneratedIncome", out var selfGenerated) && selfGenerated.GetBoolean(),
        IsNonEssential = payload.TryGetProperty("isNonEssential", out var nonEssential) && nonEssential.GetBoolean()
    };

    private static string SettlementDto(SettlementMode value) => value switch { SettlementMode.Payable => "CreatePayable", SettlementMode.NonCash => "NoCashImpact", _ => value.ToString() };
    private static string BillingDto(BillingCadence value) => value switch { BillingCadence.Daily => "Day", BillingCadence.Weekly => "Week", BillingCadence.Monthly => "Month", BillingCadence.Yearly => "Year", BillingCadence.None => "Once", _ => value.ToString() };
    private static string RecognitionDto(RecognitionMethod value) => value switch { RecognitionMethod.StraightLineDaily => "ServicePeriodDaily", RecognitionMethod.NaturalMonth => "CalendarMonth", _ => value.ToString() };
    private static SettlementMode SettlementFromPayload(JsonElement payload) => StringValue(payload, "settlementMode", "PaidFromAccount") switch
    {
        "IncludedInOpeningBalance" => SettlementMode.IncludedInOpeningBalance,
        "CreatePayable" => SettlementMode.Payable,
        "NoCashImpact" => SettlementMode.NonCash,
        _ => SettlementMode.PaidFromAccount
    };
    private static BillingCadence BillingFromPayload(JsonElement payload) => StringValue(payload, "billingCadence", "Once") switch
    {
        "Day" => BillingCadence.Daily, "Week" => BillingCadence.Weekly, "Month" => BillingCadence.Monthly, "Year" => BillingCadence.Yearly, "Custom" => BillingCadence.Custom, _ => BillingCadence.None
    };
    private static RecognitionMethod RecognitionFromPayload(JsonElement payload) => StringValue(payload, "recognitionMethod", "PaymentDate") switch
    {
        "ServicePeriodDaily" => RecognitionMethod.StraightLineDaily, "CalendarMonth" => RecognitionMethod.NaturalMonth, "PreviousPeriod" => RecognitionMethod.PreviousPeriod, "CustomFormula" => RecognitionMethod.CustomFormula, _ => RecognitionMethod.Immediate
    };
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
        if (dialog.ShowDialog(this) != true) return;
        var preview = _store.PreviewBackup(dialog.FileName);
        var dates = preview.FirstRecordDate is null ? "无记录日期" : $"{preview.FirstRecordDate:yyyy-MM-dd} 至 {preview.LastRecordDate:yyyy-MM-dd}";
        var legacy = preview.IsLegacy ? Environment.NewLine + "旧版 v2.0 备份，将自动升级。" : string.Empty;
        var message = "备份：" + preview.FileName + Environment.NewLine
            + "创建时间：" + preview.CreatedAt.ToString("g") + Environment.NewLine
            + $"记录：{preview.RecordCount} 条，回收站：{preview.DeletedRecordCount} 条" + Environment.NewLine
            + $"自定义指标：{preview.CustomMetricCount} 个，预警规则：{preview.WarningRuleCount} 条" + Environment.NewLine
            + "日期范围：" + dates + Environment.NewLine
            + "校验：" + (preview.IsChecksumValid ? "通过" : "失败") + legacy + Environment.NewLine + Environment.NewLine
            + "恢复将覆盖当前账本，是否继续？";
        if (!preview.IsChecksumValid) throw new InvalidDataException("备份校验失败，已取消恢复。");
        if (MessageBox.Show(this, message, "恢复备份预览", MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
        _store.CreateProtectionSnapshot("before-restore", _vm.State);
        _vm.ReplaceState(_store.Restore(dialog.FileName));
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

    private object BuildSnapshot(string? requestedPeriod = null)
    {
        var period=requestedPeriod??_selectedPeriod;var state=_vm.State;var active=state.Records.Where(x=>x.DeletedAt is null).ToList();var range=MetricEngine.Range(period,customStart:_customStart,customEnd:_customEnd);var values=_metricEngine.Calculate(active,range,state.CustomMetrics,safetyTarget:state.Settings.SafetyBufferTarget,metricDefinitions:state.MetricDefinitions,formulaDefinitions:state.FormulaDefinitions);var analysis=BuildAnalysis(active.Count,values);
        object RecordDto(FinanceRecord r)=>new{id=r.Id.ToString(),date=r.Date.ToString("yyyy-MM-dd"),type=r.Type.ToString(),r.TypeLabel,r.Amount,displayAmount=state.HideAllAmounts?"••••••":r.DisplayAmount,r.Category,r.Account,r.CategoryId,metricTargetIds=r.MetricTargetIds,settlementMode=SettlementDto(r.SettlementMode),settlementDate=r.SettlementDate?.ToString("yyyy-MM-dd"),financialAccountId=r.FinancialAccountId?.ToString(),serviceStart=r.ServiceStart?.ToString("yyyy-MM-dd"),serviceEndExclusive=r.ServiceEndExclusive?.ToString("yyyy-MM-dd"),billingCadence=BillingDto(r.BillingCadence),recognitionMethod=RecognitionDto(r.RecognitionMethod),r.Note,r.CustomMetricId,r.IncomeSource,r.IsSelfGeneratedIncome,r.IsNonEssential,createdAt=r.CreatedAt.ToString("O"),updatedAt=r.UpdatedAt.ToString("O"),deletedAt=r.DeletedAt?.ToString("O")};
        return new
        {
            metrics=_vm.Metrics.Select(m=>{var v=values.GetValueOrDefault(m.Id);return new{m.Id,m.Name,subtitle=m.Subtitle+(period=="all"?"":" · "+range.Label),displayValue=FormatMetric(m,v,state.HideAllAmounts||m.IsHidden),rawValue=v?.Value,dataStatus=v?.Status??"not-computable",periodLabel=range.Label,hasActiveWarning=state.WarningEvents.Any(e=>(e.Status is "active" or "read")&&state.WarningRules.Any(r=>r.Id==e.RuleId&&(r.MetricId==m.Id||r.Conditions.Any(c=>c.MetricId==m.Id)))),m.CanRecord,m.IsCustom,m.IsHidden,m.IsEnabled,displayFormat=m.DisplayFormat.ToString()};}),
            records=_vm.Records.Select(RecordDto),deletedRecords=state.Records.Where(x=>x.DeletedAt is not null).OrderByDescending(x=>x.DeletedAt).Select(RecordDto),state.HideAllAmounts,state.ThemeName,state.CustomThemeCss,dataFile=_store.DataFile,dataDirectory=_store.DataDirectory,dataFileSize=File.Exists(_store.DataFile)?new FileInfo(_store.DataFile).Length:0,schemaVersion=state.SchemaVersion,backupFormatVersion=2,appVersion="3.0.0",dataIntegrityStatus=_store.RecoveryRequired?"需要恢复":"正常",lastSavedAt=state.UpdatedAt.ToString("O"),lastBackupAt=state.LastBackupAt?.ToString("O"),lastBackupPath=_store.LastBackupPath,lastProtectionSnapshot=_store.LastProtectionSnapshot,recordCount=active.Count,deletedRecordCount=state.Records.Count(x=>x.DeletedAt is not null),customMetricCount=state.CustomMetrics.Count,warningRuleCount=state.WarningRules.Count,selectedPeriod=period,periodLabel=range.Label,
            profiles=_profileIndex.Profiles.Select(x=>new{id=x.Id.ToString(),x.Name,createdAt=x.CreatedAt.ToString("O"),lastOpenedAt=x.LastOpenedAt.ToString("O"),x.IsArchived}),activeProfileId=_profileIndex.ActiveProfileId.ToString(),categories=state.Categories.Select(x=>new{id=x.Id.ToString(),x.Name,parentId=x.ParentId?.ToString(),x.IsSystem,x.IsArchived,x.SortOrder}),financialAccounts=state.FinancialAccounts.Select(x=>new{id=x.Id.ToString(),x.Name,kind=x.Kind.ToString(),balanceSide=x.BalanceSide.ToString(),openingDate=x.OpeningDate.ToString("yyyy-MM-dd"),x.OpeningBalance,x.IncludeInAvailableCash,x.IsArchived}),metricTemplates=MetricTemplateCatalog.All.Select(x=>new{x.Id,group="常用",x.Name,x.Description,displayFormat=x.DisplayFormat.ToString(),periodBehavior=x.PeriodBehavior.ToString(),defaultFormula=FormulaTokens(x.Formula),x.AcceptsDirectRecordAssignment}),formulas=state.FormulaDefinitions.Select(x=>new{id=x.Id.ToString(),scope=x.Scope.ToString(),x.Version,resultType=x.ResultType.ToString(),dependencies=x.Dependencies}),dashboardLayout=state.DashboardLayouts.FirstOrDefault(x=>x.Breakpoint=="desktop"),fixedAssets=state.FixedAssets.Where(x=>x.Status!=FixedAssetStatus.Archived).Select(x=>new{id=x.Id.ToString(),x.Name,categoryId=x.CategoryId?.ToString(),acquiredDate=x.InServiceDate.ToString("yyyy-MM-dd"),inServiceDate=x.InServiceDate.ToString("yyyy-MM-dd"),x.Cost,residualValue=x.SalvageValue,usefulLifeMonths=x.UsefulLifeMonths,depreciationMethod=x.Method.ToString()}),warningRules=state.WarningRules.Select(RuleDto),warningEvents=state.WarningEvents.OrderByDescending(x=>x.TriggeredAt).Select(EventDto),settings=state.Settings,recovery=new{required=_store.RecoveryRequired,message=_store.RecoveryMessage,damagedFile=_store.DamagedFile,availableSnapshots=_store.GetAvailableSnapshots()},analysis
        };
    }
    private object BuildMetricDetail(JsonElement payload)
    {
        var id=StringValue(payload,"id");var period=StringValue(payload,"period","all");var metric=_vm.Metrics.FirstOrDefault(x=>x.Id==id)??throw new InvalidOperationException("找不到该指标。");var range=MetricEngine.Range(period,ParseDate(payload,"anchor"),ParseDate(payload,"start"),ParseDate(payload,"end"));var active=_vm.State.Records.Where(x=>x.DeletedAt is null).ToList();var values=_metricEngine.Calculate(active,range,_vm.State.CustomMetrics,safetyTarget:_vm.State.Settings.SafetyBufferTarget,metricDefinitions:_vm.State.MetricDefinitions,formulaDefinitions:_vm.State.FormulaDefinitions);var value=values.GetValueOrDefault(id)??new MetricValue(id,null,"not-computable","暂无法计算",[]);var records=active.Where(x=>value.Evidence.Contains(x.Id)).OrderByDescending(x=>x.Date).ToList();
        decimal Contribution(FinanceRecord r)=>id switch{"fixedcost"=>r.Type==FinanceRecordType.FixedCost?r.Amount:0,"variablecost"=>r.Type==FinanceRecordType.VariableCost?r.Amount:0,"fixedassets"=>r.Type==FinanceRecordType.FixedAssetPurchase?r.Amount:0,"payables"=>r.Type==FinanceRecordType.PayableCreated?r.Amount:r.Type==FinanceRecordType.PayablePayment?-r.Amount:0,"assets"=>r.Type==FinanceRecordType.Income?r.Amount:r.Type is FinanceRecordType.FixedCost or FinanceRecordType.VariableCost or FinanceRecordType.PayablePayment?-r.Amount:0,"netassets"=>r.Type==FinanceRecordType.Income?r.Amount:r.Type is FinanceRecordType.FixedCost or FinanceRecordType.VariableCost or FinanceRecordType.PayableCreated?-r.Amount:0,"inflow"=>r.Type==FinanceRecordType.Income?r.Amount:0,"outflow"=>r.Type is FinanceRecordType.FixedCost or FinanceRecordType.VariableCost or FinanceRecordType.FixedAssetPurchase or FinanceRecordType.PayablePayment?r.Amount:0,"totalcost" or "nonessential"=>r.Type is FinanceRecordType.FixedCost or FinanceRecordType.VariableCost?r.Amount:0,_ when metric.IsCustom=>r.Type==FinanceRecordType.CustomDecrease?-r.Amount:r.Amount,_=>r.Type==FinanceRecordType.Income?r.Amount:r.Type is FinanceRecordType.PayableCreated?0:-r.Amount};
        var contributions=records.Select(r=>{var amount=Contribution(r);return new{recordId=r.Id.ToString(),date=r.Date.ToString("yyyy-MM-dd"),label=r.TypeLabel+(string.IsNullOrWhiteSpace(r.Category)?"":" · "+r.Category),amount,displayAmount=_vm.State.HideAllAmounts||metric.IsHidden?"••••••":(amount>=0?"+ ":"− ")+Math.Abs(amount).ToString("N2"),direction=amount>0?"positive":amount<0?"negative":"neutral",explanation=$"参与公式：{value.Formula}"};});
        var end=range.End>DateTime.Today?DateTime.Today:range.End;var trendStart=range.Start==DateTime.MinValue.Date?end.AddDays(-13):range.Start;var span=(end-trendStart).Days;var step=span>31?Math.Max(1,span/11):1;var trend=new List<object>();for(var day=trendStart;day<=end;day=day.AddDays(step)){var dayRange=id is "cash" or "assets" or "netassets" or "fixedassets" or "payables" or "runway" or "chain"?new DateRange(DateTime.MinValue,day,day.ToString("MM-dd")):new DateRange(day,day,day.ToString("MM-dd"));var point=_metricEngine.Calculate(active,dayRange,_vm.State.CustomMetrics,safetyTarget:_vm.State.Settings.SafetyBufferTarget,metricDefinitions:_vm.State.MetricDefinitions,formulaDefinitions:_vm.State.FormulaDefinitions).GetValueOrDefault(id)?.Value;if(point is not null)trend.Add(new{label=day.ToString("MM-dd"),value=point.Value});}
        var components=new List<object>();if(id is "runway" or "chain"){var cash=values["cash"];var burn=values["burn"];components.Add(new{label="现金储备",value=cash.Value,sourceMetricId="cash",status=cash.Status});components.Add(new{label="Burn Rate",value=burn.Value,sourceMetricId="burn",status=burn.Status});}else if(id=="dependency"){components.Add(new{label="父母支持收入",value=active.Where(x=>x.Date.Date>=range.Start&&x.Date.Date<=end&&x.IncomeSource=="父母支持").Sum(x=>x.Amount),sourceMetricId="inflow",status="ready"});components.Add(new{label="总收入",value=values["inflow"].Value,sourceMetricId="inflow",status=values["inflow"].Status});}
        return new{metric=new{id=metric.Id,name=metric.Name,subtitle=metric.Subtitle,displayValue=FormatMetric(metric,value,_vm.State.HideAllAmounts||metric.IsHidden),rawValue=value.Value,dataStatus=value.Status,periodLabel=range.Label,metric.CanRecord,metric.IsCustom,metric.IsHidden,metric.IsEnabled,displayFormat=metric.DisplayFormat.ToString()},periodLabel=range.Label,value=value.Value,comparisonValue=(decimal?)null,definition=metric.Subtitle,formula=value.Formula,dataStatus=value.Status,trend,components,contributions};
    }
    private static object BuildAnalysis(int recordCount, IReadOnlyDictionary<string, MetricValue> values)
    {
        decimal? Value(string id) => values.GetValueOrDefault(id)?.Value;
        var income = Value("inflow") ?? 0;
        var fixedCost = Value("fixedcost") ?? 0;
        var variableCost = Value("variablecost") ?? 0;
        var dependency = Value("dependency");
        var totalCost = Value("totalcost") ?? 0;
        var runway = Value("runway");
        var fixedRatio = totalCost == 0 ? 0 : fixedCost / totalCost * 100;
        var verdict = recordCount == 0 ? "还没有记录。先录入一笔收入或成本，分析会立即出现。"
            : runway is null ? "数据还在积累；满三个完整月后，资金跑道会更可靠。"
            : runway < 1 ? "资金链偏紧，建议先保留现金并检查可削减成本。"
            : runway < 3 ? "目前可维持时间有限，优先建立安全垫。"
            : "资金链暂时稳健，可以继续观察成本结构。";
        return new { empty = recordCount == 0, income, fixedCost, variableCost, dependency, fixedCostRatio = fixedRatio, verdict };
    }
    private void Respond(string id, bool ok, object? data, string? error)
    {
        if (WebView.CoreWebView2 is null) return;
        WebView.CoreWebView2.PostWebMessageAsJson(JsonSerializer.Serialize(new { id, ok, data, error }, _json));
    }

    private static DateTime? ParseDate(JsonElement payload, string property) => payload.TryGetProperty(property, out var value) && value.ValueKind == JsonValueKind.String && DateTime.TryParse(value.GetString(), out var date) ? date.Date : null;
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
