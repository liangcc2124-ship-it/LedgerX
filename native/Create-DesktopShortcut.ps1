param(
    [string]$ExePath = (Join-Path $PSScriptRoot '..\release-native\LedgerX.exe'),
    [string]$IconPath = (Join-Path $PSScriptRoot '..\assets\ledgerx-custom-v2.ico')
)
$resolvedExe = [System.IO.Path]::GetFullPath($ExePath)
if (-not (Test-Path -LiteralPath $resolvedExe)) { throw "找不到 LedgerX.exe：$resolvedExe" }
$resolvedIcon = [System.IO.Path]::GetFullPath($IconPath)
if (-not (Test-Path -LiteralPath $resolvedIcon)) { throw "找不到 LedgerX 图标：$resolvedIcon" }
$desktop = [Environment]::GetFolderPath('Desktop')
$shortcutPath = Join-Path $desktop 'LedgerX.lnk'
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $resolvedExe
$shortcut.WorkingDirectory = Split-Path -Parent $resolvedExe
$shortcut.IconLocation = "$resolvedIcon,0"
$shortcut.Description = 'LedgerX 个人财务管理'
$shortcut.Save()
Write-Output $shortcutPath
