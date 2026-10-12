[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$guard = Join-Path $root 'scripts/ptv-mobile-workspace-preflight.ps1'
$initialStatus = @(git -C $root status --porcelain=v1 -uall)
$passCount = 0

function Invoke-Case {
    param(
        [string]$Name,
        [string]$Mode,
        [int]$ExpectedExit,
        [string]$ScriptPath = $guard,
        [string]$WorkingDirectory = $root,
        [hashtable]$Environment = @{}
    )
    $saved = @{}
    foreach ($key in $Environment.Keys) {
        $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $Environment[$key], 'Process')
    }
    try {
        Push-Location -LiteralPath $WorkingDirectory
        try {
            $savedErrorPreference = $ErrorActionPreference
            try {
                $ErrorActionPreference = 'Continue'
                $null = & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $ScriptPath -Mode $Mode 2>&1
                $actualExit = $LASTEXITCODE
            } finally {
                $ErrorActionPreference = $savedErrorPreference
            }
        } finally {
            Pop-Location
        }
    } finally {
        foreach ($key in $Environment.Keys) {
            [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process')
        }
    }
    if ($actualExit -ne $ExpectedExit) {
        throw "$Name expected exit $ExpectedExit but got $actualExit."
    }
    $script:passCount++
    Write-Output "PASS $Name"
}

Invoke-Case -Name 'approved root audit' -Mode Audit -ExpectedExit 0
Invoke-Case -Name 'approved root local build' -Mode Build -ExpectedExit 0
Invoke-Case -Name 'unapproved release package denied' -Mode Package -ExpectedExit 1
Invoke-Case -Name 'unapproved device installation denied' -Mode Install -ExpectedExit 1
Invoke-Case -Name 'unapproved publication denied' -Mode Publish -ExpectedExit 1
Invoke-Case -Name 'wrong version override denied' -Mode Build -ExpectedExit 1 -Environment @{ PIGGIETV_VERSION = '0.0.0' }
Invoke-Case -Name 'wrong remote denied' -Mode Build -ExpectedExit 1 -Environment @{
    GIT_CONFIG_COUNT = '1'
    GIT_CONFIG_KEY_0 = 'remote.origin.url'
    GIT_CONFIG_VALUE_0 = 'https://example.invalid/other.git'
}
Invoke-Case -Name 'wrong Git common directory denied' -Mode Build -ExpectedExit 1 -Environment @{
    GIT_COMMON_DIR = (Join-Path $env:TEMP 'ptv-mobile-nonexistent-common')
}
$fixture = Join-Path ([IO.Path]::GetFullPath($env:TEMP)) ("ptv-mobile-guard-" + [guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Path (Join-Path $fixture 'scripts') -Force | Out-Null
    Copy-Item -LiteralPath $guard -Destination (Join-Path $fixture 'scripts/ptv-mobile-workspace-preflight.ps1')
    Invoke-Case -Name 'unauthorized source copy denied' -Mode Build -ExpectedExit 1 -ScriptPath (Join-Path $fixture 'scripts/ptv-mobile-workspace-preflight.ps1') -WorkingDirectory $fixture
    Invoke-Case -Name 'outside working directory denied' -Mode Build -ExpectedExit 1 -WorkingDirectory $fixture
} finally {
    $resolvedFixture = [IO.Path]::GetFullPath($fixture)
    $resolvedTemp = [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
    if (-not $resolvedFixture.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to remove fixture outside TEMP.'
    }
    if (Test-Path -LiteralPath $fixture) {
        Remove-Item -LiteralPath $fixture -Recurse -Force
    }
}
$buildOutput = & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $guard -Mode Build
$reportedDirtyCount = [regex]::Match(($buildOutput -join ' '), 'DIRTY_PATHS=(\d+)').Groups[1].Value
$actualDirtyCount = @(git -C $root status --porcelain=v1 -uall).Count
if ([int]$reportedDirtyCount -ne $actualDirtyCount) {
    throw "Guard counted $reportedDirtyCount dirty paths; Git reports $actualDirtyCount."
}
Write-Output 'PASS dirty path count'
$finalStatus = @(git -C $root status --porcelain=v1 -uall)
if (($initialStatus -join [Environment]::NewLine) -ne ($finalStatus -join [Environment]::NewLine)) {
    throw 'Guard tests changed tracked or untracked source.'
}
Write-Output 'PASS no source mutation'
Write-Output "RESULT $passCount preflight cases passed"
