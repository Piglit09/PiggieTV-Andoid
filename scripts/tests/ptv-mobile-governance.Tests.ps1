[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$guard = Join-Path $root 'scripts/ptv-mobile-workspace-preflight.ps1'
. (Join-Path $root 'scripts/ptv-mobile-approval-rules.ps1')
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

function Invoke-TargetCase {
    param([string]$Name, [string]$Mode, [hashtable]$Values, [bool]$Allowed)
    $policy = [pscustomobject]$Values
    $denied = $false
    try {
        Assert-PTVMobileTargetApproval -Mode $Mode -Policy $policy
    } catch {
        $denied = $true
    }
    if ($denied -eq $Allowed) {
        throw "$Name returned an unexpected target authorization result."
    }
    $script:passCount++
    Write-Output "PASS $Name"
}

function Invoke-AclCase {
    param(
        [string]$Name,
        [string]$OwnerSid,
        [string]$RuleSid,
        [Security.AccessControl.RegistryRights]$Rights,
        [bool]$Allowed
    )
    $acl = [Security.AccessControl.RegistrySecurity]::new()
    $acl.SetOwner([Security.Principal.SecurityIdentifier]::new($OwnerSid))
    $acl.AddAccessRule([Security.AccessControl.RegistryAccessRule]::new(
        [Security.Principal.SecurityIdentifier]::new($RuleSid),
        $Rights,
        [Security.AccessControl.AccessControlType]::Allow
    ))
    $denied = $false
    try {
        Assert-PTVMobileRegistryAcl -Acl $acl -Path 'in-memory fixture'
    } catch {
        $denied = $true
    }
    if ($denied -eq $Allowed) {
        throw "$Name returned an unexpected Registry ACL result."
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
Invoke-TargetCase -Name 'ordinary serial classified as test device' -Mode Install -Values @{ targetClass = 'TEST_DEVICE' } -Allowed $true
Invoke-TargetCase -Name 'production device denied without separate approval' -Mode Install -Values @{ targetClass = 'PRODUCTION_DEVICE'; productionApproval = $false; productionApprovalId = '' } -Allowed $false
Invoke-TargetCase -Name 'production device denied without approval identity' -Mode Install -Values @{ targetClass = 'PRODUCTION_DEVICE'; productionApproval = $true; productionApprovalId = '' } -Allowed $false
Invoke-TargetCase -Name 'production device requires explicit approval identity' -Mode Install -Values @{ targetClass = 'PRODUCTION_DEVICE'; productionApproval = $true; productionApprovalId = 'operator-record-fixture' } -Allowed $true
Invoke-TargetCase -Name 'unknown device class denied' -Mode Install -Values @{ targetClass = 'UNKNOWN' } -Allowed $false
Invoke-TargetCase -Name 'publication denied without separate release approval' -Mode Publish -Values @{ targetClass = 'RELEASE_CHANNEL'; releaseApproval = $false; releaseApprovalId = '' } -Allowed $false
Invoke-TargetCase -Name 'publication requires explicit release approval identity' -Mode Publish -Values @{ targetClass = 'RELEASE_CHANNEL'; releaseApproval = $true; releaseApprovalId = 'operator-record-fixture' } -Allowed $true
$currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
Invoke-AclCase -Name 'task-owned approval ancestor denied' -OwnerSid $currentSid -RuleSid 'S-1-5-18' -Rights ReadKey -Allowed $false
Invoke-AclCase -Name 'writable approval ancestor denied' -OwnerSid 'S-1-5-18' -RuleSid 'S-1-1-0' -Rights CreateSubKey -Allowed $false
Invoke-AclCase -Name 'non-writable approval ancestor accepted' -OwnerSid 'S-1-5-18' -RuleSid 'S-1-1-0' -Rights ReadKey -Allowed $true
foreach ($installer in @('tools/books/install-debug-apk.ps1', 'tools/android-auto/install-debug-apk.ps1')) {
    $installerSource = Get-Content -LiteralPath (Join-Path $root $installer) -Raw
    $resolvedIndex = $installerSource.IndexOf('$resolvedApkPath = Resolve-ApkPath', [StringComparison]::Ordinal)
    $lockIndex = $installerSource.IndexOf('$apkLock = [IO.File]::Open', [StringComparison]::Ordinal)
    $guardIndex = $installerSource.IndexOf('-ArtifactPath $resolvedApkPath', [StringComparison]::Ordinal)
    $adbIndex = $installerSource.IndexOf('$deviceResult = Get-AuthorizedDevices', [StringComparison]::Ordinal)
    $installIndex = $installerSource.IndexOf('install -r $resolvedApkPath', [StringComparison]::Ordinal)
    $disposeIndex = $installerSource.IndexOf('$apkLock.Dispose()', [StringComparison]::Ordinal)
    if ($resolvedIndex -lt 0 -or $lockIndex -le $resolvedIndex -or
        $guardIndex -le $lockIndex -or $adbIndex -le $guardIndex -or
        $installIndex -le $adbIndex -or $disposeIndex -le $installIndex) {
        throw "$installer does not hold the resolved APK read lock through device installation."
    }
    $passCount++
    Write-Output "PASS $installer canonical APK approval order"
}
$fixture = Join-Path ([IO.Path]::GetFullPath($env:TEMP)) ("ptv-mobile-guard-" + [guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Path (Join-Path $fixture 'scripts') -Force | Out-Null
    $fixtureApk = Join-Path $fixture 'mock.apk'
    [IO.File]::WriteAllText($fixtureApk, 'disposable fixture')
    $readLock = [IO.File]::Open($fixtureApk, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        $null = Get-FileHash -LiteralPath $fixtureApk -Algorithm SHA256
        $writeDenied = $false
        try {
            $writeHandle = [IO.File]::Open($fixtureApk, [IO.FileMode]::Open, [IO.FileAccess]::Write, [IO.FileShare]::None)
            $writeHandle.Dispose()
        } catch {
            $writeDenied = $true
        }
        if (-not $writeDenied) { throw 'Read lock permitted concurrent fixture APK mutation.' }
        $passCount++
        Write-Output 'PASS held APK read lock denies concurrent writes'
    } finally {
        $readLock.Dispose()
    }
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
