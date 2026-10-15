# Generates RemoDroid app icons from the source logo using System.Drawing (Windows PowerShell 5.1+).
# Usage: powershell -ExecutionPolicy Bypass -File scripts/generate-icons.ps1
# Outputs Android launcher/adaptive icons, an in-app logo, and Windows PNG/ICO icons.
param(
    [string]$Source = (Join-Path $PSScriptRoot "..\RemoDroid Remote Control Logo.png")
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$res = Join-Path $root "android\app\src\main\res"
$winRes = Join-Path $root "windows\src\main\resources"
$winIcons = Join-Path $root "windows\icons"
$navy = [System.Drawing.ColorTranslator]::FromHtml("#0B1220")

$src = [System.Drawing.Image]::FromFile((Resolve-Path $Source))

# The phone-and-cursor mark sits in the upper middle of the 1254x1254 logo.
$markRect = New-Object System.Drawing.Rectangle 410, 270, 440, 440

function New-Canvas([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    return @($bmp, $g)
}

function Save-Mark([string]$path, [int]$size, [double]$markFraction, [bool]$round, [bool]$transparent) {
    $bmp, $g = New-Canvas $size
    if (-not $transparent) {
        $brush = New-Object System.Drawing.SolidBrush $navy
        if ($round) { $g.FillEllipse($brush, 0, 0, $size, $size) } else { $g.FillRectangle($brush, 0, 0, $size, $size) }
        $brush.Dispose()
    }
    $m = [int]($size * $markFraction)
    $o = [int](($size - $m) / 2)
    $g.DrawImage($src, (New-Object System.Drawing.Rectangle $o, $o, $m, $m), $markRect, [System.Drawing.GraphicsUnit]::Pixel)
    New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}

function Save-Full([string]$path, [int]$size) {
    $bmp, $g = New-Canvas $size
    $g.DrawImage($src, 0, 0, $size, $size)
    New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}

# Legacy launcher icons (pre-Android 8) and round variants.
$densities = @{ "mdpi" = 48; "hdpi" = 72; "xhdpi" = 96; "xxhdpi" = 144; "xxxhdpi" = 192 }
foreach ($d in $densities.Keys) {
    Save-Mark (Join-Path $res "mipmap-$d\ic_launcher.png") $densities[$d] 0.78 $false $false
    Save-Mark (Join-Path $res "mipmap-$d\ic_launcher_round.png") $densities[$d] 0.70 $true $false
    # Adaptive icon foreground: 108dp canvas, mark inside the 66dp safe zone.
    $fg = [int]($densities[$d] * 108 / 48)
    Save-Mark (Join-Path $res "mipmap-$d\ic_launcher_foreground.png") $fg 0.58 $false $true
}

# In-app branding: full logo with wordmark and tagline.
Save-Full (Join-Path $res "drawable-nodpi\remodroid_logo.png") 512
Save-Mark (Join-Path $res "drawable-nodpi\remodroid_mark.png") 256 1.0 $false $true

# Windows: window icon resource and installer icon.
Save-Full (Join-Path $winRes "remodroid_logo.png") 512
Save-Mark (Join-Path $winRes "remodroid_icon.png") 256 0.80 $false $false
Save-Mark (Join-Path $winIcons "remodroid.png") 256 0.80 $false $false

# Build a multi-resolution .ico (PNG-compressed entries, supported since Windows Vista).
$icoSizes = @(16, 24, 32, 48, 64, 128, 256)
$pngs = @()
foreach ($s in $icoSizes) {
    $tmp = [System.IO.Path]::GetTempFileName()
    Save-Mark $tmp $s 0.84 $false $false
    $pngs += ,([System.IO.File]::ReadAllBytes($tmp))
    Remove-Item $tmp
}
$ms = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter $ms
$bw.Write([UInt16]0); $bw.Write([UInt16]1); $bw.Write([UInt16]$icoSizes.Count)
$offset = 6 + 16 * $icoSizes.Count
for ($i = 0; $i -lt $icoSizes.Count; $i++) {
    $s = $icoSizes[$i]; $b = if ($s -ge 256) { 0 } else { $s }
    $bw.Write([byte]$b); $bw.Write([byte]$b); $bw.Write([byte]0); $bw.Write([byte]0)
    $bw.Write([UInt16]1); $bw.Write([UInt16]32)
    $bw.Write([UInt32]$pngs[$i].Length); $bw.Write([UInt32]$offset)
    $offset += $pngs[$i].Length
}
foreach ($p in $pngs) { $bw.Write($p) }
New-Item -ItemType Directory -Force $winIcons | Out-Null
[System.IO.File]::WriteAllBytes((Join-Path $winIcons "remodroid.ico"), $ms.ToArray())
$bw.Dispose()

$src.Dispose()
Write-Output "Icons generated."
