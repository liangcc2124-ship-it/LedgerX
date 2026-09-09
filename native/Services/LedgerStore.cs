using System.Text.Json;
using LedgerX.Models;

namespace LedgerX.Services;

public sealed class LedgerStore
{
    private readonly JsonSerializerOptions _jsonOptions = new() { WriteIndented = true };
    public string DataDirectory { get; } = Environment.GetEnvironmentVariable("LEDGERX_DATA_DIR")
        ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LedgerX");
    public string DataFile => Path.Combine(DataDirectory, "ledger.json");

    public LedgerState Load()
    {
        Directory.CreateDirectory(DataDirectory);
        if (!File.Exists(DataFile)) return new LedgerState();
        try
        {
            return JsonSerializer.Deserialize<LedgerState>(File.ReadAllText(DataFile), _jsonOptions) ?? new LedgerState();
        }
        catch
        {
            var damaged = Path.Combine(DataDirectory, $"ledger.damaged-{DateTime.Now:yyyyMMdd-HHmmss}.json");
            File.Copy(DataFile, damaged, true);
            return new LedgerState();
        }
    }

    public void Save(LedgerState state)
    {
        Directory.CreateDirectory(DataDirectory);
        var temporary = DataFile + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(state, _jsonOptions));
        File.Move(temporary, DataFile, true);
    }

    public void Backup(string targetPath, LedgerState state) => File.WriteAllText(targetPath, JsonSerializer.Serialize(state, _jsonOptions));

    public LedgerState Restore(string sourcePath) => JsonSerializer.Deserialize<LedgerState>(File.ReadAllText(sourcePath), _jsonOptions)
        ?? throw new InvalidDataException("备份文件内容无效。");
}
