# Exports MSVC_HOME for build.bat.
#
# build.bat only auto-detects Visual Studio under
# "Microsoft Visual Studio\{2017,2019,2022}\{Community,Enterprise,Professional}".
# The runner images now ship VS 2026 at "Microsoft Visual Studio\18\Enterprise",
# which that search misses, so resolve the install with vswhere instead and pass
# it in via MSVC_HOME (which build.bat honours when already set).

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
if (-not (Test-Path $vswhere)) {
    $onPath = Get-Command vswhere.exe -ErrorAction SilentlyContinue
    if (-not $onPath) { throw "vswhere.exe not found at '$vswhere' nor on PATH" }
    $vswhere = $onPath.Source
}

$installs = & $vswhere -products * -all -prerelease -format json | ConvertFrom-Json

$selected = $installs |
    Sort-Object { [version] $_.installationVersion } -Descending |
    Where-Object { Test-Path (Join-Path $_.installationPath 'VC\Auxiliary\Build\vcvarsall.bat') } |
    Select-Object -First 1

if (-not $selected) {
    $installs | ForEach-Object { Write-Host "found VS $($_.installationVersion) at $($_.installationPath) (no vcvarsall.bat)" }
    throw 'No Visual Studio install with a C++ toolset (vcvarsall.bat) was found'
}

Write-Host "MSVC_HOME=$($selected.installationPath) (Visual Studio $($selected.installationVersion))"
"MSVC_HOME=$($selected.installationPath)" | Out-File -FilePath $env:GITHUB_ENV -Append -Encoding utf8
