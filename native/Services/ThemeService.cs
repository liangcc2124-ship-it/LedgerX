using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Media;

namespace LedgerX.Services;

public sealed class ThemeService
{
    private static readonly Dictionary<string, string> ColorKeys = new(StringComparer.OrdinalIgnoreCase)
    {
        ["--lx-background"] = "AppBackgroundBrush", ["--lx-surface"] = "SurfaceBrush",
        ["--lx-sidebar"] = "SidebarBrush", ["--lx-primary"] = "PrimaryBrush",
        ["--lx-primary-hover"] = "PrimaryHoverBrush", ["--lx-text"] = "TextBrush",
        ["--lx-muted"] = "MutedTextBrush", ["--lx-border"] = "BorderBrush",
        ["--lx-income"] = "IncomeBrush", ["--lx-cost"] = "CostBrush",
        ["--lx-warning"] = "WarningBrush", ["--lx-selected"] = "SelectedBrush"
    };

    public static IReadOnlyList<string> BuiltInThemes { get; } = ["暖铜", "石墨", "深海蓝"];

    public void ApplyBuiltIn(string name)
    {
        var file = name switch { "石墨" => "graphite.css", "深海蓝" => "navy.css", _ => "warm-copper.css" };
        var resource = typeof(ThemeService).Assembly.GetManifestResourceStream($"LedgerX.Themes.{file}")
            ?? throw new InvalidOperationException($"找不到内置皮肤：{name}");
        using var reader = new StreamReader(resource);
        ApplyCss(reader.ReadToEnd());
    }

    public void ApplyCssFile(string path) => ApplyCss(File.ReadAllText(path));

    public void ApplyCss(string css)
    {
        foreach (Match match in Regex.Matches(css, @"(?<name>--lx-[\w-]+)\s*:\s*(?<value>[^;{}]+)\s*;"))
        {
            var name = match.Groups["name"].Value;
            var value = match.Groups["value"].Value.Trim();
            if (ColorKeys.TryGetValue(name, out var key) && TryColor(value, out var color))
                Application.Current.Resources[key] = new SolidColorBrush(color);
            else if (name.Equals("--lx-card-radius", StringComparison.OrdinalIgnoreCase)
                     && double.TryParse(value.Replace("px", "", StringComparison.OrdinalIgnoreCase), out var radius)
                     && radius is >= 0 and <= 32)
                Application.Current.Resources["CardRadius"] = new CornerRadius(radius);
            else if (name.Equals("--lx-font-family", StringComparison.OrdinalIgnoreCase) && value.Length is > 0 and < 100)
                Application.Current.Resources["UiFontFamily"] = new FontFamily(value.Trim('"', '\''));
        }
    }

    public string ExportCss() => string.Join(Environment.NewLine,
        ":root {",
        $"  --lx-background: {Hex("AppBackgroundBrush")};",
        $"  --lx-surface: {Hex("SurfaceBrush")};",
        $"  --lx-sidebar: {Hex("SidebarBrush")};",
        $"  --lx-primary: {Hex("PrimaryBrush")};",
        $"  --lx-primary-hover: {Hex("PrimaryHoverBrush")};",
        $"  --lx-text: {Hex("TextBrush")};",
        $"  --lx-muted: {Hex("MutedTextBrush")};",
        $"  --lx-border: {Hex("BorderBrush")};",
        $"  --lx-income: {Hex("IncomeBrush")};",
        $"  --lx-cost: {Hex("CostBrush")};",
        $"  --lx-warning: {Hex("WarningBrush")};",
        $"  --lx-selected: {Hex("SelectedBrush")};",
        $"  --lx-card-radius: {((CornerRadius)Application.Current.Resources["CardRadius"]).TopLeft}px;",
        $"  --lx-font-family: \"{Application.Current.Resources["UiFontFamily"]}\";",
        "}", "");

    private static bool TryColor(string value, out Color color)
    {
        try { color = (Color)ColorConverter.ConvertFromString(value); return value.StartsWith('#'); }
        catch { color = Colors.Transparent; return false; }
    }

    private static string Hex(string key)
    {
        var c = ((SolidColorBrush)Application.Current.Resources[key]).Color;
        return $"#{c.R:X2}{c.G:X2}{c.B:X2}";
    }
}
