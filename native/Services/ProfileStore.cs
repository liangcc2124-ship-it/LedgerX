using System.Text.Json;
using LedgerX.Models;

namespace LedgerX.Services;

public sealed class ProfileStore
{
    private readonly JsonSerializerOptions _json = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true
    };

    public string RootDirectory { get; }

    public ProfileStore(string? rootDirectory = null)
    {
        RootDirectory = rootDirectory
            ?? Environment.GetEnvironmentVariable("LEDGERX_DATA_DIR")
            ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LedgerX");
    }

    private string IndexFile => Path.Combine(RootDirectory, "profiles.json");

    public ProfileIndex Load()
    {
        Directory.CreateDirectory(RootDirectory);
        if (File.Exists(IndexFile))
        {
            var index = JsonSerializer.Deserialize<ProfileIndex>(File.ReadAllText(IndexFile), _json);
            if (index is not null && index.Profiles.Count > 0 && index.ActiveProfileId != Guid.Empty)
                return index;
        }

        var profile = new ProfileDefinition { Name = "默认空间" };
        var created = new ProfileIndex { ActiveProfileId = profile.Id, Profiles = [profile] };
        Save(created);
        return created;
    }

    public void Save(ProfileIndex index)
    {
        Directory.CreateDirectory(RootDirectory);
        var temporary = IndexFile + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(index, _json));
        File.Move(temporary, IndexFile, true);
    }

    public string DataDirectory(ProfileIndex index, Guid profileId)
    {
        var defaultProfile = index.Profiles.FirstOrDefault();
        return defaultProfile is not null && profileId == defaultProfile.Id
            ? RootDirectory
            : Path.Combine(RootDirectory, "Profiles", profileId.ToString("N"));
    }
}
