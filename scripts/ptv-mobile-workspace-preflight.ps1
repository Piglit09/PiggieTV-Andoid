[CmdletBinding()]
param(
    [ValidateSet('Audit', 'Edit', 'Build', 'Package', 'Install', 'Publish')]
    [string]$Mode = 'Audit',
    [string]$TargetId,
    [string]$ArtifactPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$approvedRoot = 'C:\Users\Piggie\Documents\GitHub\PiggieTV-Android'
$approvedRemote = 'https://github.com/Piglit09/PiggieTV-Andoid.git'
$approvedAppId = 'com.piggietv.android'
$approvedArtifactRoot = 'C:\Users\Piggie\Documents\GitHub\_PTV-Artifacts\Builds\Android-Mobile'
$registryPolicyKey = 'Registry::HKEY_LOCAL_MACHINE\SOFTWARE\PiggieTV\WorkspaceAuthority\AndroidMobile'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$isWindows = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$comparison = if ($isWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
. (Join-Path $PSScriptRoot 'ptv-mobile-approval-rules.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Normalize-Path {
    param([string]$Path)
    return [IO.Path]::GetFullPath($Path).TrimEnd([char[]]@('\', '/'))
}

function Assert-NoReparseAncestor {
    param([string]$Path)
    $entry = Get-Item -LiteralPath $Path -Force
    while ($null -ne $entry) {
        if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "A source path ancestor is a reparse point: $($entry.FullName)"
        }
        $entry = $entry.Parent
    }
}

function Invoke-Git {
    param([string[]]$Arguments)
    $lines = & git -C $repoRoot @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Git identity check failed: $($Arguments -join ' ')"
    }
    return ($lines -join [Environment]::NewLine).Trim()
}

function Assert-ProtectedRegistryPolicy {
    param(
        [string]$Head,
        [string]$Tree,
        [string]$Version,
        [string]$CommonDirectory
    )
    $key = Get-Item -LiteralPath $registryPolicyKey -ErrorAction SilentlyContinue
    Assert-True ($null -ne $key) 'Operator-controlled Android Mobile approval policy is absent.'

    $registryAncestors = @(
        'Registry::HKEY_LOCAL_MACHINE',
        'Registry::HKEY_LOCAL_MACHINE\SOFTWARE',
        'Registry::HKEY_LOCAL_MACHINE\SOFTWARE\PiggieTV',
        'Registry::HKEY_LOCAL_MACHINE\SOFTWARE\PiggieTV\WorkspaceAuthority',
        $registryPolicyKey
    )
    foreach ($registryPath in $registryAncestors) {
        Assert-True (Test-Path -LiteralPath $registryPath) "Approval Registry ancestor is absent: $registryPath"
        Assert-PTVMobileRegistryAcl -Acl (Get-Acl -LiteralPath $registryPath) -Path $registryPath
    }

    $raw = (Get-ItemProperty -LiteralPath $registryPolicyKey -Name PolicyJson -ErrorAction Stop).PolicyJson
    Assert-True (-not [string]::IsNullOrWhiteSpace($raw)) 'Approval policy is empty.'
    $policy = $raw | ConvertFrom-Json
    Assert-True ($policy.schemaVersion -eq 1) 'Approval policy schema is invalid.'
    Assert-True ($policy.projectId -ceq 'PTV_ANDROID_MOBILE') 'Approval policy project is invalid.'
    Assert-True ((Normalize-Path $policy.sourceRoot).Equals((Normalize-Path $repoRoot), $comparison)) 'Approval policy source root differs.'
    Assert-True ((Normalize-Path $policy.gitCommonDir).Equals((Normalize-Path $CommonDirectory), $comparison)) 'Approval policy Git common directory differs.'
    Assert-True ($policy.remote -ceq $approvedRemote) 'Approval policy remote differs.'
    Assert-True ($policy.applicationId -ceq $approvedAppId) 'Approval policy application identity differs.'
    Assert-True ($policy.sourceCommit -ceq $Head) 'Approval policy source commit differs.'
    Assert-True ($policy.sourceTree -ceq $Tree) 'Approval policy source tree differs.'
    Assert-True ($policy.version -ceq $Version) 'Approval policy source version differs.'
    Assert-True ((Normalize-Path $policy.artifactRoot).Equals((Normalize-Path $approvedArtifactRoot), $comparison)) 'Approval policy artifact root differs.'
    Assert-True (-not [string]::IsNullOrWhiteSpace($policy.approvalId)) 'Approval record ID is absent.'
    Assert-True (-not [string]::IsNullOrWhiteSpace($policy.rollbackReference)) 'Verified rollback reference is absent.'
    Assert-True ([DateTimeOffset]::Parse($policy.expiresUtc) -gt [DateTimeOffset]::UtcNow) 'Approval record has expired.'
    Assert-True (@($policy.allowedModes) -ccontains $Mode) 'Requested operation is not approved.'

    if ($Mode -in @('Install', 'Publish')) {
        Assert-True (-not [string]::IsNullOrWhiteSpace($TargetId)) 'An exact target ID is required.'
        Assert-True ($TargetId -ceq $policy.targetId) 'Target does not match the approval record.'
        Assert-PTVMobileTargetApproval -Mode $Mode -Policy $policy
        Assert-True (-not [string]::IsNullOrWhiteSpace($ArtifactPath)) 'An exact artifact is required.'
        Assert-True (Test-Path -LiteralPath $ArtifactPath -PathType Leaf) 'Approved artifact is missing.'
        $resolvedArtifact = Normalize-Path $ArtifactPath
        $artifactPrefix = (Normalize-Path $approvedArtifactRoot) + [IO.Path]::DirectorySeparatorChar
        Assert-True ($resolvedArtifact.StartsWith($artifactPrefix, $comparison)) 'Artifact path escapes the approved artifact root.'
        Assert-NoReparseAncestor (Split-Path -Parent $resolvedArtifact)
        $artifactIsLink = (((Get-Item -LiteralPath $resolvedArtifact).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)
        Assert-True (-not $artifactIsLink) 'Artifact is a reparse point.'
        $digest = (Get-FileHash -LiteralPath $ArtifactPath -Algorithm SHA256).Hash
        Assert-True ($digest -ceq $policy.artifactSha256) 'Artifact hash differs from approval record.'
        $manifestPath = Normalize-Path $policy.artifactManifestPath
        Assert-True ($manifestPath.StartsWith($artifactPrefix, $comparison)) 'Artifact manifest escapes the approved artifact root.'
        Assert-True (Test-Path -LiteralPath $manifestPath -PathType Leaf) 'Artifact manifest is missing.'
        Assert-NoReparseAncestor (Split-Path -Parent $manifestPath)
        $manifestDigest = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash
        Assert-True ($manifestDigest -ceq $policy.artifactManifestSha256) 'Artifact manifest hash differs.'
        $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        Assert-True ($manifest.sourceCommit -ceq $Head -and $manifest.sourceTree -ceq $Tree -and
            $manifest.version -ceq $Version) 'Artifact manifest source provenance differs.'
        $artifactName = Split-Path -Leaf $resolvedArtifact
        Assert-True (@($manifest.files | Where-Object { $_.path -ceq $artifactName -and $_.sha256 -ceq $digest }).Count -eq 1) 'Artifact is absent or duplicated in manifest.'
    }
}

try {
    Assert-True (Test-Path -LiteralPath $repoRoot -PathType Container) 'Source root does not exist.'
    Assert-NoReparseAncestor $repoRoot
    $cwd = Normalize-Path (Get-Location).Path
    Assert-NoReparseAncestor $cwd
    $root = Normalize-Path $repoRoot
    Assert-True ($cwd.Equals($root, $comparison) -or
        $cwd.StartsWith($root + [IO.Path]::DirectorySeparatorChar, $comparison)) 'Working directory is outside the source root.'

    $actualRoot = Normalize-Path (Invoke-Git @('rev-parse', '--show-toplevel'))
    Assert-True ($actualRoot.Equals($root, $comparison)) 'Git root differs from the script source root.'
    $commonReported = Invoke-Git @('rev-parse', '--git-common-dir')
    $commonDirectory = if ([IO.Path]::IsPathRooted($commonReported)) {
        Normalize-Path $commonReported
    } else {
        Normalize-Path (Join-Path $repoRoot $commonReported)
    }
    $expectedCommon = Normalize-Path (Join-Path $repoRoot '.git')
    Assert-True ($commonDirectory.Equals($expectedCommon, $comparison)) 'Git common directory is unexpected.'
    Assert-True (Test-Path -LiteralPath $expectedCommon -PathType Container) 'Approved source is a linked worktree or has invalid Git metadata.'

    $remote = Invoke-Git @('config', '--get', 'remote.origin.url')
    Assert-True ($remote -ceq $approvedRemote) 'Git remote is unexpected.'
    $head = Invoke-Git @('rev-parse', 'HEAD')
    $tree = Invoke-Git @('rev-parse', 'HEAD^{tree}')
    Assert-True ($head -cmatch '^[0-9a-f]{40}$') 'Git HEAD is invalid.'
    Assert-True ($tree -cmatch '^[0-9a-f]{40}$') 'Git tree is invalid.'
    $branch = Invoke-Git @('branch', '--show-current')
    $statusText = Invoke-Git @('status', '--porcelain=v1', '-uall')
    $status = @($statusText -split '\r?\n' | Where-Object { $_.Length -gt 0 })

    $versionLines = @(Get-Content -LiteralPath (Join-Path $repoRoot 'gradle.properties') |
        Where-Object { $_ -match '^piggietv\.version=(.+)$' })
    Assert-True ($versionLines.Count -eq 1) 'Official version source must have one PiggieTV version.'
    $version = ($versionLines[0] -split '=', 2)[1].Trim()
    Assert-True ($version -cmatch '^\d+\.\d+\.\d+$') 'Official source version is malformed.'
    if (-not [string]::IsNullOrWhiteSpace($env:PIGGIETV_VERSION)) {
        Assert-True ($env:PIGGIETV_VERSION -ceq $version) 'PIGGIETV_VERSION differs from gradle.properties.'
    }
    $buildScript = Get-Content -LiteralPath (Join-Path $repoRoot 'app/build.gradle.kts') -Raw
    Assert-True ($buildScript -match 'applicationId\s*=\s*"com\.piggietv\.android"') 'Application identity is unexpected.'

    $ciExactRef = -not $isWindows -and
        $env:GITHUB_ACTIONS -eq 'true' -and
        $env:GITHUB_REPOSITORY -ceq 'Piglit09/PiggieTV-Andoid' -and
        $env:GITHUB_SHA -ceq $head -and
        -not [string]::IsNullOrWhiteSpace($env:GITHUB_WORKSPACE) -and
        (Normalize-Path $env:GITHUB_WORKSPACE).Equals($root, $comparison) -and
        $status.Count -eq 0
    if ($ciExactRef) {
        Assert-True ($Mode -in @('Audit', 'Build')) 'Exact-ref CI exception permits local validation only.'
    } else {
        Assert-True ($isWindows) 'Non-Windows source requires an exact-ref CI context.'
        Assert-True ($root.Equals((Normalize-Path $approvedRoot), $comparison)) 'This is not the approved Android Mobile source root.'
    }

    if ($Mode -in @('Package', 'Install', 'Publish')) {
        Assert-True ($status.Count -eq 0) 'Release packaging and external actions require clean committed source.'
        Assert-ProtectedRegistryPolicy -Head $head -Tree $tree -Version $version -CommonDirectory $commonDirectory
    } elseif ($TargetId -or $ArtifactPath) {
        throw 'Target and artifact arguments are only valid for approved external actions.'
    }

    Write-Output "PTV_MOBILE_PREFLIGHT=PASS MODE=$Mode HEAD=$head BRANCH=$branch VERSION=$version DIRTY_PATHS=$($status.Count)"
    exit 0
} catch {
    [Console]::Error.WriteLine("PTV Mobile preflight denied: $($_.Exception.Message)")
    exit 1
}
