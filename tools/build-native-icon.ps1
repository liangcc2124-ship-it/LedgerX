param(
    [string]$SourcePng,
    [string]$OutputBase = 'ledgerx-custom'
)

Add-Type -AssemblyName System.Drawing

if ($SourcePng) {
    $resolvedSource = [System.IO.Path]::GetFullPath($SourcePng)
    if (-not (Test-Path -LiteralPath $resolvedSource)) { throw "找不到图标源文件：$resolvedSource" }
    $source = [System.Drawing.Image]::FromFile($resolvedSource)
    if ($OutputBase -notmatch '^[a-zA-Z0-9_-]+$') { throw 'OutputBase 只能包含字母、数字、下划线和连字符。' }
    $pngPath = Join-Path $PSScriptRoot "..\assets\$OutputBase.png"
    $icoPath = Join-Path $PSScriptRoot "..\assets\$OutputBase.ico"
    $sizes = @(16, 20, 24, 32, 40, 48, 64, 96, 128, 256)
    $frames = New-Object 'System.Collections.Generic.List[byte[]]'
    foreach ($frameSize in $sizes) {
        $bitmap = New-Object System.Drawing.Bitmap($frameSize, $frameSize, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
        $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.Clear([System.Drawing.Color]::Transparent)
        $graphics.DrawImage($source, 0, 0, $frameSize, $frameSize)
        $memory = New-Object System.IO.MemoryStream
        $bitmap.Save($memory, [System.Drawing.Imaging.ImageFormat]::Png)
        $frames.Add($memory.ToArray())
        if ($frameSize -eq 256) { $bitmap.Save($pngPath, [System.Drawing.Imaging.ImageFormat]::Png) }
        $memory.Dispose(); $graphics.Dispose(); $bitmap.Dispose()
    }

    $file = [System.IO.File]::Create($icoPath)
    $writer = New-Object System.IO.BinaryWriter($file)
    $writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]$sizes.Count)
    $offset = 6 + (16 * $sizes.Count)
    for ($index = 0; $index -lt $sizes.Count; $index++) {
        $dimension = if ($sizes[$index] -eq 256) { 0 } else { $sizes[$index] }
        $writer.Write([byte]$dimension); $writer.Write([byte]$dimension)
        $writer.Write([byte]0); $writer.Write([byte]0)
        $writer.Write([uint16]1); $writer.Write([uint16]32)
        $writer.Write([uint32]$frames[$index].Length); $writer.Write([uint32]$offset)
        $offset += $frames[$index].Length
    }
    foreach ($frame in $frames) { $writer.Write($frame) }
    $writer.Dispose(); $file.Dispose(); $source.Dispose()
    Write-Output $pngPath
    Write-Output $icoPath
    return
}

$size = 256
$bitmap = New-Object System.Drawing.Bitmap($size, $size)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.Clear([System.Drawing.Color]::Transparent)

$path = New-Object System.Drawing.Drawing2D.GraphicsPath
$radius = 52
$diameter = $radius * 2
$path.AddArc(12, 12, $diameter, $diameter, 180, 90)
$path.AddArc($size - 12 - $diameter, 12, $diameter, $diameter, 270, 90)
$path.AddArc($size - 12 - $diameter, $size - 12 - $diameter, $diameter, $diameter, 0, 90)
$path.AddArc(12, $size - 12 - $diameter, $diameter, $diameter, 90, 90)
$path.CloseFigure()
$brush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(154, 106, 58))
$graphics.FillPath($brush, $path)

$font = New-Object System.Drawing.Font('Segoe UI', 126, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$format = New-Object System.Drawing.StringFormat
$format.Alignment = [System.Drawing.StringAlignment]::Center
$format.LineAlignment = [System.Drawing.StringAlignment]::Center
$white = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
$graphics.DrawString('L', $font, $white, (New-Object System.Drawing.RectangleF(0, -3, $size, $size)), $format)

$pngPath = Join-Path $PSScriptRoot '..\assets\ledgerx-native.png'
$icoPath = Join-Path $PSScriptRoot '..\assets\ledgerx-native.ico'
$bitmap.Save($pngPath, [System.Drawing.Imaging.ImageFormat]::Png)
$icon = [System.Drawing.Icon]::FromHandle($bitmap.GetHicon())
$stream = [System.IO.File]::Create($icoPath)
$icon.Save($stream)
$stream.Dispose(); $icon.Dispose(); $font.Dispose(); $white.Dispose(); $brush.Dispose(); $path.Dispose(); $graphics.Dispose(); $bitmap.Dispose()
