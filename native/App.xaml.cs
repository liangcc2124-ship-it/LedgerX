using System.IO.Pipes;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Windows;
using LedgerX.Models;
using LedgerX.Services;
using LedgerX.ViewModels;

[assembly: System.Runtime.CompilerServices.InternalsVisibleTo("LedgerX.Tests")]

namespace LedgerX;

public partial class App : Application
{
    private SingleInstanceCoordinator? _singleInstance;

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

        _singleInstance = new SingleInstanceCoordinator(
            SingleInstanceIdentity.GetCurrentUserScope(),
            () => Dispatcher.BeginInvoke(RestoreExistingWindow));

        if (!_singleInstance.TryAcquirePrimary())
        {
            var activated = _singleInstance.TryActivateExisting();
            _singleInstance.Dispose();
            _singleInstance = null;
            Shutdown(activated ? 0 : 1);
            return;
        }

        MainWindow = new MainWindow();
        _singleInstance.StartListening();
        MainWindow.Show();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _singleInstance?.Dispose();
        _singleInstance = null;
        base.OnExit(e);
    }

    private void RestoreExistingWindow()
    {
        if (MainWindow is not Window window)
        {
            return;
        }

        if (!window.IsVisible)
        {
            window.Show();
        }

        if (window.WindowState == WindowState.Minimized)
        {
            window.WindowState = WindowState.Normal;
        }

        // Briefly making the window topmost is the reliable WPF path through Windows focus protection.
        window.Topmost = true;
        window.Activate();
        window.Focus();
        window.Topmost = false;
    }
}

internal static class SingleInstanceIdentity
{
    private const string ProductPrefix = "LedgerX";

    internal static string GetCurrentUserScope()
    {
        var sid = WindowsIdentity.GetCurrent().User?.Value;
        return string.IsNullOrWhiteSpace(sid)
            ? $"{Environment.UserDomainName}\\{Environment.UserName}"
            : sid;
    }

    internal static string MutexName(string userScope) => $"Local\\{ProductPrefix}.{ScopeHash(userScope)}";

    internal static string PipeName(string userScope) => $"{ProductPrefix}.{ScopeHash(userScope)}.activation";

    private static string ScopeHash(string userScope)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(userScope);
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(userScope));
        return Convert.ToHexString(hash.AsSpan(0, 16));
    }
}

internal sealed class SingleInstanceCoordinator : IDisposable
{
    private const string ActivationMessage = "activate";
    private const string Acknowledgement = "activated";
    private readonly Action _onActivation;
    private readonly string _mutexName;
    private readonly string _pipeName;
    private readonly CancellationTokenSource _shutdown = new();
    private readonly object _pipeLock = new();
    private Mutex? _mutex;
    private NamedPipeServerStream? _activeServer;
    private Task? _listenerTask;
    private bool _ownsMutex;
    private bool _disposed;

    internal SingleInstanceCoordinator(string userScope, Action onActivation)
    {
        ArgumentNullException.ThrowIfNull(onActivation);
        _onActivation = onActivation;
        _mutexName = SingleInstanceIdentity.MutexName(userScope);
        _pipeName = SingleInstanceIdentity.PipeName(userScope);
    }

    internal bool TryAcquirePrimary()
    {
        ThrowIfDisposed();
        _mutex = new Mutex(initiallyOwned: true, _mutexName, out var createdNew);
        _ownsMutex = createdNew;
        return createdNew;
    }

    internal void StartListening()
    {
        ThrowIfDisposed();
        if (!_ownsMutex)
        {
            throw new InvalidOperationException("Only the primary instance can accept activation requests.");
        }

        _listenerTask ??= ListenAsync(_shutdown.Token);
    }

    internal bool TryActivateExisting()
    {
        for (var attempt = 0; attempt < 20; attempt++)
        {
            try
            {
                using var timeout = new CancellationTokenSource(TimeSpan.FromMilliseconds(300));
                using var client = new NamedPipeClientStream(
                    ".", _pipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
                client.ConnectAsync(timeout.Token).GetAwaiter().GetResult();

                using var writer = new StreamWriter(client, new UTF8Encoding(false), 1024, leaveOpen: true)
                {
                    AutoFlush = true
                };
                using var reader = new StreamReader(client, Encoding.UTF8, detectEncodingFromByteOrderMarks: false, leaveOpen: true);
                writer.WriteLine(ActivationMessage);
                var acknowledgement = reader.ReadLineAsync(timeout.Token).AsTask().GetAwaiter().GetResult();
                if (string.Equals(acknowledgement, Acknowledgement, StringComparison.Ordinal))
                {
                    return true;
                }
            }
            catch (OperationCanceledException)
            {
                // The primary instance can own the mutex briefly before its pipe listener is ready.
            }
            catch (IOException)
            {
                // Retry while the first instance is still starting or shutting down.
            }

            Thread.Sleep(100);
        }

        return false;
    }

    private async Task ListenAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                using var server = new NamedPipeServerStream(
                    _pipeName, PipeDirection.InOut, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous);
                lock (_pipeLock)
                {
                    _activeServer = server;
                }

                await server.WaitForConnectionAsync(cancellationToken).ConfigureAwait(false);
                using var reader = new StreamReader(server, Encoding.UTF8, detectEncodingFromByteOrderMarks: false, leaveOpen: true);
                using var writer = new StreamWriter(server, new UTF8Encoding(false), 1024, leaveOpen: true) { AutoFlush = true };
                var message = await reader.ReadLineAsync(cancellationToken).ConfigureAwait(false);
                if (string.Equals(message, ActivationMessage, StringComparison.Ordinal))
                {
                    _onActivation();
                    await writer.WriteLineAsync(Acknowledgement).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (IOException) when (!cancellationToken.IsCancellationRequested)
            {
                await Task.Delay(100, cancellationToken).ConfigureAwait(false);
            }
            finally
            {
                lock (_pipeLock)
                {
                    _activeServer = null;
                }
            }
        }
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _shutdown.Cancel();
        lock (_pipeLock)
        {
            _activeServer?.Dispose();
        }

        try
        {
            _listenerTask?.Wait(TimeSpan.FromSeconds(1));
        }
        catch (AggregateException)
        {
            // Cancellation is expected during application shutdown.
        }

        if (_ownsMutex)
        {
            _mutex?.ReleaseMutex();
            _ownsMutex = false;
        }

        _mutex?.Dispose();
        _shutdown.Dispose();
    }

    private void ThrowIfDisposed()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
    }
}
