[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../ptv-mobile-approval-rules.ps1')
$passed = 0

function Assert-Case {
    param([string]$Name, [scriptblock]$Action, [bool]$Allowed)
    $denied = $false
    try { & $Action | Out-Null } catch { $denied = $true }
    if ($denied -eq $Allowed) { throw "$Name produced unexpected authorization result." }
    $script:passed++
    Write-Output "PASS $Name"
}

$sourceRoot = 'C:\Users\Piggie\Documents\GitHub\PiggieTV-Android'
$artifactRoot = 'C:\Users\Piggie\Documents\GitHub\_PTV-Artifacts\Builds\Android-Mobile'
$ids = @('PTV_WEB', 'PTV_ANDROID_TV', 'PTV_WEBOS', 'PTV_ANDROID_MOBILE')
$projects = @(
    [pscustomobject]@{ projectId='PTV_WEB'; state='HOLD'; reason='Web governance is separately controlled' },
    [pscustomobject]@{ projectId='PTV_ANDROID_TV'; state='ACTIVE'; sourceRoot='C:\Users\Piggie\Documents\GitHub\PiggieTV-AndoidTV'; gitCommonDir='C:\Users\Piggie\Documents\GitHub\PiggieTV-AndoidTV\.git'; remote='https://github.com/Piglit09/PiggieTV-AndoidTV.git'; applicationId='com.piggietv.androidtv'; versionSource='gradle.properties'; artifactRoots=@('C:\Users\Piggie\Documents\GitHub\_PTV-Artifacts\Builds\Android-TV'); allowedModes=@('Edit','Build'); packageEnabled=$false; externalOperations=@() },
    [pscustomobject]@{ projectId='PTV_WEBOS'; state='ACTIVE'; sourceRoot='C:\Users\Piggie\Documents\GitHub\PiggieTV-WebOS'; gitCommonDir='C:\Users\Piggie\Documents\GitHub\PiggieTV-WebOS\.git'; remote='https://github.com/Piglit09/PiggieTV-WebOS.git'; applicationId='com.piggietv.webos'; versionSource='package.json'; artifactRoots=@('C:\Users\Piggie\Documents\GitHub\_PTV-Artifacts\Builds\webOS'); allowedModes=@('Edit','Build'); packageEnabled=$false; externalOperations=@() },
    [pscustomobject]@{ projectId='PTV_ANDROID_MOBILE'; state='ACTIVE'; sourceRoot=$sourceRoot; gitCommonDir="$sourceRoot\.git"; remote='https://github.com/Piglit09/PiggieTV-Andoid.git'; applicationId='com.piggietv.android'; versionSource='gradle.properties:piggietv.version'; artifactRoots=@($artifactRoot); allowedModes=@('Edit','Build'); packageEnabled=$false; externalOperations=@() }
)
$base = [pscustomobject]@{ schemaVersion=2; policyId='fixture-v2'; issuedUtc='2026-10-19T00:00:00Z'; expiresUtc='2099-10-19T00:00:00Z'; projects=$projects; approvals=@() }
function Copy-Policy { return (($base | ConvertTo-Json -Depth 20 -Compress) | ConvertFrom-Json) }
function Resolve-Fixture {
    param($Policy)
    Resolve-PTVMobileSharedPolicy -Policy $Policy -Root $sourceRoot -CommonDirectory "$sourceRoot\.git" -Remote 'https://github.com/Piglit09/PiggieTV-Andoid.git' -ApplicationId 'com.piggietv.android' -VersionSource 'gradle.properties:piggietv.version' -ArtifactRoot $artifactRoot -Mode Build
}
Assert-Case 'valid exact mobile build entry' { Resolve-Fixture (Copy-Policy) } $true
Assert-Case 'cross-project entry cannot authorize mobile' {
    $p=Copy-Policy; $p.projects=@($p.projects | Where-Object projectId -ne 'PTV_ANDROID_MOBILE'); Resolve-Fixture $p
} $false
Assert-Case 'duplicate project rejected' {
    $p=Copy-Policy; $p.projects+= $p.projects[-1]; Resolve-Fixture $p
} $false
Assert-Case 'unknown project rejected' {
    $p=Copy-Policy; $p.projects[-1].projectId='UNKNOWN'; Resolve-Fixture $p
} $false
Assert-Case 'overlapping writable roots rejected' {
    $p=Copy-Policy; $p.projects[1].sourceRoot=$sourceRoot; Resolve-Fixture $p
} $false
Assert-Case 'wrong mobile remote rejected' {
    $p=Copy-Policy; $p.projects[-1].remote='https://example.invalid/other.git'; Resolve-Fixture $p
} $false
Assert-Case 'expired shared policy rejected' {
    $p=Copy-Policy; $p.expiresUtc='2020-01-01T00:00:00Z'; Resolve-Fixture $p
} $false
Assert-Case 'nonempty unimplemented approvals rejected' {
    $p=Copy-Policy; $p.approvals=@([pscustomobject]@{projectId='PTV_ANDROID_MOBILE'}); Resolve-Fixture $p
} $false
Assert-Case 'package permission not inferred from build' {
    $p=Copy-Policy
    Resolve-PTVMobileSharedPolicy -Policy $p -Root $sourceRoot -CommonDirectory "$sourceRoot\.git" -Remote 'https://github.com/Piglit09/PiggieTV-Andoid.git' -ApplicationId 'com.piggietv.android' -VersionSource 'gradle.properties:piggietv.version' -ArtifactRoot $artifactRoot -Mode Package
} $false
$legacy=[pscustomobject]@{ projectId='PTV_WEBOS'; sourceRoot='C:\Users\Piggie\Documents\GitHub\PiggieTV-WebOS' }
Assert-Case 'legacy webOS format recognized before operator v2 floor' {
    $format=Resolve-PTVMobilePolicyFormat -Policy $legacy -MinimumSchemaVersion 1
    if ($format -cne 'LegacyWebOS') { throw 'Wrong policy format.' }
} $true
Assert-Case 'legacy format denied after protected v2 floor' {
    Resolve-PTVMobilePolicyFormat -Policy $legacy -MinimumSchemaVersion 2
} $false
Assert-Case 'explicit v2 format selected after protected v2 floor' {
    $format=Resolve-PTVMobilePolicyFormat -Policy (Copy-Policy) -MinimumSchemaVersion 2
    if ($format -cne 'SharedV2') { throw 'Wrong policy format.' }
} $true
Assert-Case 'partial v2 marker cannot fall back' {
    Resolve-PTVMobilePolicyFormat -Policy ([pscustomobject]@{ projects=@() }) -MinimumSchemaVersion 1
} $false
Assert-Case 'literal protected REG_SZ accepted' {
    Assert-PTVMobileLiteralRegistryValueKind -Kind ([Microsoft.Win32.RegistryValueKind]::String)
} $true
Assert-Case 'environment-expanded policy value denied' {
    Assert-PTVMobileLiteralRegistryValueKind -Kind ([Microsoft.Win32.RegistryValueKind]::ExpandString)
} $false

$debugApproval=[pscustomobject]@{ approvedGradleTask=':app:assembleLibreDebug'; approvedVariant='libreDebug'; applicationId='com.piggietv.android'; packageApplicationId='com.piggietv.android.debug' }
Assert-Case 'exact Libre debug task accepted by isolated contract' { Assert-PTVMobileDebugPackageTask -Policy $debugApproval -GradleTasks @(':app:assembleLibreDebug') } $true
Assert-Case 'other flavor rejected' { Assert-PTVMobileDebugPackageTask -Policy $debugApproval -GradleTasks @(':app:assembleProprietaryDebug') } $false
Assert-Case 'release task rejected' { Assert-PTVMobileDebugPackageTask -Policy $debugApproval -GradleTasks @(':app:assembleLibreRelease') } $false
Assert-Case 'task abbreviation rejected' { Assert-PTVMobileDebugPackageTask -Policy $debugApproval -GradleTasks @(':app:assembleLibreDeb') } $false
Assert-Case 'extra task rejected' { Assert-PTVMobileDebugPackageTask -Policy $debugApproval -GradleTasks @(':app:assembleLibreDebug', ':app:help') } $false
Assert-Case 'release variant cannot authorize debug' {
    $p=[pscustomobject]@{ approvedGradleTask=':app:assembleLibreDebug'; approvedVariant='libreRelease'; applicationId='com.piggietv.android'; packageApplicationId='com.piggietv.android.debug' }
    Assert-PTVMobileDebugPackageTask -Policy $p -GradleTasks @(':app:assembleLibreDebug')
} $false
$debugEnvironment=[pscustomobject]@{ approvedVariant='libreDebug'; approvedJavaHome='C:\Tools\Jdk21'; approvedSdkRoot='C:\Tools\AndroidSdk'; approvedOutputDirectory="$sourceRoot\app\build\outputs\apk\libre\debug" }
Assert-Case 'exact debug package toolchain and output accepted' {
    Assert-PTVMobileDebugPackageEnvironment -Policy $debugEnvironment -JavaHome 'C:\Tools\Jdk21' -SdkRoot 'C:\Tools\AndroidSdk' -Root $sourceRoot
} $true
Assert-Case 'other SDK denied' {
    Assert-PTVMobileDebugPackageEnvironment -Policy $debugEnvironment -JavaHome 'C:\Tools\Jdk21' -SdkRoot 'C:\OtherSdk' -Root $sourceRoot
} $false
Assert-Case 'output path override denied' {
    $p=[pscustomobject]@{ approvedVariant='libreDebug'; approvedJavaHome='C:\Tools\Jdk21'; approvedSdkRoot='C:\Tools\AndroidSdk'; approvedOutputDirectory="$sourceRoot\app\build\outputs\apk\proprietary\debug" }
    Assert-PTVMobileDebugPackageEnvironment -Policy $p -JavaHome 'C:\Tools\Jdk21' -SdkRoot 'C:\Tools\AndroidSdk' -Root $sourceRoot
} $false
$commit = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
$tree = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
$completeApproval = [pscustomobject]@{
    schemaVersion=1; projectId='PTV_ANDROID_MOBILE'; sourceRoot=$sourceRoot; gitCommonDir="$sourceRoot\.git"
    remote='https://github.com/Piglit09/PiggieTV-Andoid.git'; applicationId='com.piggietv.android'
    sourceCommit=$commit; sourceTree=$tree; version='0.2.71'; artifactRoot=$artifactRoot
    approvalId='independent-operator-fixture'; rollbackReference='verified-local-fixture'; expiresUtc='2099-10-19T00:00:00Z'
    allowedModes=@('Package'); productionApproval=$false; releaseApproval=$false
    approvedGradleTask=':app:assembleLibreDebug'; approvedVariant='libreDebug'; packageApplicationId='com.piggietv.android.debug'
    approvedJavaHome='C:\Tools\Jdk21'; approvedSdkRoot='C:\Tools\AndroidSdk'; approvedOutputDirectory="$sourceRoot\app\build\outputs\apk\libre\debug"
}
function Check-FullApproval {
    param($Policy)
    Assert-PTVMobileDebugPackageApproval -Policy $Policy -Head $commit -Tree $tree -Version '0.2.71' -Root $sourceRoot -CommonDirectory "$sourceRoot\.git" -Remote 'https://github.com/Piglit09/PiggieTV-Andoid.git' -ArtifactRoot $artifactRoot -GradleTasks @(':app:assembleLibreDebug') -JavaHome 'C:\Tools\Jdk21' -SdkRoot 'C:\Tools\AndroidSdk'
}
Assert-Case 'complete exact debug package record accepted in isolated fixture' { Check-FullApproval $completeApproval } $true
Assert-Case 'complete record with wrong source commit denied' {
    $p=$completeApproval | ConvertTo-Json -Depth 10 | ConvertFrom-Json
    $p.sourceCommit='cccccccccccccccccccccccccccccccccccccccc'; Check-FullApproval $p
} $false
Assert-Case 'complete record with install permission denied' {
    $p=$completeApproval | ConvertTo-Json -Depth 10 | ConvertFrom-Json
    $p.allowedModes=@('Package','Install'); Check-FullApproval $p
} $false
Assert-Case 'complete record with textual schema version denied' {
    $p=$completeApproval | ConvertTo-Json -Depth 10 | ConvertFrom-Json
    $p.schemaVersion='1'; Check-FullApproval $p
} $false
Assert-Case 'complete record with textual release flag denied' {
    $p=$completeApproval | ConvertTo-Json -Depth 10 | ConvertFrom-Json
    $p.releaseApproval='false'; Check-FullApproval $p
} $false
Write-Output "RESULT $passed shared-policy cases passed"
