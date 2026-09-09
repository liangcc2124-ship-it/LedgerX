using System.Windows;
using System.Windows.Controls;
using LedgerX.Models;
using LedgerX.Services;
using Microsoft.Win32;

namespace LedgerX.Views;

public partial class ThemeWindow : Window
{
    private readonly ThemeService _themes; private readonly LedgerState _state;
    public ThemeWindow(ThemeService themes, LedgerState state)
    {
        InitializeComponent(); _themes=themes; _state=state;
        ThemeList.ItemsSource = ThemeService.BuiltInThemes; ThemeList.SelectedItem = state.ThemeName;
    }
    private void ThemeChanged(object sender, SelectionChangedEventArgs e)
    { if (ThemeList.SelectedItem is string name) { _themes.ApplyBuiltIn(name); _state.ThemeName=name; _state.CustomThemePath=null; } }
    private void ImportClick(object sender, RoutedEventArgs e)
    { var d=new OpenFileDialog{Filter="LedgerX 皮肤 (*.css)|*.css"}; if(d.ShowDialog(this)==true){try{_themes.ApplyCssFile(d.FileName);_state.CustomThemePath=d.FileName;_state.ThemeName="自定义 CSS";ThemeList.SelectedItem=null;}catch(Exception ex){MessageBox.Show(this,ex.Message,"导入失败");}} }
    private void ExportClick(object sender, RoutedEventArgs e)
    { var d=new SaveFileDialog{Filter="CSS 文件 (*.css)|*.css",FileName="ledgerx-theme.css"}; if(d.ShowDialog(this)==true) File.WriteAllText(d.FileName,_themes.ExportCss()); }
    private void DoneClick(object sender, RoutedEventArgs e){DialogResult=true;}
}
