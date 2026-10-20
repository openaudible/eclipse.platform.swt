<#
.SYNOPSIS
Fails the build if any DLL in a directory is not the expected machine architecture.

.DESCRIPTION
Reads the COFF machine field out of each PE image's header. Guards against a
cross-compile silently falling back to the host toolchain, which produces
x64 binaries inside the aarch64 fragment.
#>
param(
    [Parameter(Mandatory = $true)][string]$Directory,
    [Parameter(Mandatory = $true)][ValidateSet('AMD64', 'ARM64')][string]$Expected
)

$ErrorActionPreference = 'Stop'

$MachineNames = @{
    0x014C = 'I386'
    0x8664 = 'AMD64'
    0xAA64 = 'ARM64'
    0x01C4 = 'ARMNT'
}

function Get-PeMachine {
    param([string]$Path)

    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $reader = New-Object System.IO.BinaryReader($stream)
        if ($reader.ReadUInt16() -ne 0x5A4D) {
            throw "$Path is not a PE image (missing MZ signature)"
        }
        $stream.Position = 0x3C
        $stream.Position = $reader.ReadUInt32()
        if ($reader.ReadUInt32() -ne 0x00004550) {
            throw "$Path is not a PE image (missing PE signature)"
        }
        return $reader.ReadUInt16()
    }
    finally {
        $stream.Dispose()
    }
}

$dlls = @(Get-ChildItem -Path $Directory -Filter '*.dll' -File)
if ($dlls.Count -eq 0) {
    throw "No DLLs found in $Directory"
}

$failed = @()
foreach ($dll in $dlls) {
    $machine = [int](Get-PeMachine $dll.FullName)
    $actual = if ($MachineNames.ContainsKey($machine)) { $MachineNames[$machine] } else { '0x{0:X4}' -f $machine }

    if ($actual -eq $Expected) {
        Write-Host ('  OK   {0,-40} {1}' -f $dll.Name, $actual)
    }
    else {
        Write-Host ('  FAIL {0,-40} {1} (expected {2})' -f $dll.Name, $actual, $Expected)
        $failed += $dll.Name
    }
}

if ($failed.Count -gt 0) {
    throw "Wrong architecture in ${Directory}: $($failed -join ', ') - expected $Expected"
}

Write-Host "All $($dlls.Count) native libraries in $Directory are $Expected."
