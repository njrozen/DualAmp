# Downloads projectM's "Cream of the Crop" preset pack (~9,800 MilkDrop presets) and the
# MilkDrop texture pack into app/src/main/assets/presets, then writes the index the app reads.
# Run once before building:  powershell -ExecutionPolicy Bypass -File tools\fetch-presets.ps1
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"  # the progress bar makes downloads very slow in PowerShell 5
$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root "app\src\main\assets\presets"
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("dualamp-presets-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force $tmp, $dest | Out-Null

function Fetch($repo, $name) {
    $zip = Join-Path $tmp "$name.zip"
    Write-Host "Downloading $repo ..."
    Invoke-WebRequest "https://github.com/projectM-visualizer/$repo/archive/refs/heads/master.zip" -OutFile $zip
    Expand-Archive $zip -DestinationPath (Join-Path $tmp $name) -Force
    return Get-ChildItem (Join-Path $tmp $name) -Directory | Select-Object -First 1
}

$presets = Fetch "presets-cream-of-the-crop" "presets"
$textures = Fetch "presets-milkdrop-texture-pack" "textures"

# Presets keep their category folders.
Get-ChildItem $presets.FullName -Directory | ForEach-Object {
    Copy-Item $_.FullName (Join-Path $dest $_.Name) -Recurse -Force
}

# Textures go flat into presets/textures.
$texDest = Join-Path $dest "textures"
New-Item -ItemType Directory -Force $texDest | Out-Null
Get-ChildItem $textures.FullName -Recurse -File -Include *.jpg, *.jpeg, *.png, *.tga, *.bmp, *.dds |
    ForEach-Object { Copy-Item $_.FullName (Join-Path $texDest $_.Name) -Force }

# index.txt lists every preset relative to assets/presets (listing assets at runtime is slow).
$milk = Get-ChildItem $dest -Recurse -File -Filter *.milk |
    ForEach-Object { $_.FullName.Substring($dest.Length + 1).Replace('\', '/') } | Sort-Object
[IO.File]::WriteAllLines((Join-Path $dest "index.txt"), [string[]]$milk)

Remove-Item $tmp -Recurse -Force
Write-Host "Done: $($milk.Count) presets in $dest"
