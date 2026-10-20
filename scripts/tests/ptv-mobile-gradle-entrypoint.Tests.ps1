[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$gradle = Join-Path $root 'gradlew.bat'
$start = @(git -C $root status --porcelain=v1 -uall)

function Invoke-GradleCase {
    param([string]$Name, [string[]]$Arguments, [int]$ExpectedExit, [string]$ExpectedText)
    Push-Location -LiteralPath $root
    try {
        $savedPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $output = & $gradle @Arguments --offline --no-daemon 2>&1
            $exitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $savedPreference }
    } finally { Pop-Location }
    if ($exitCode -ne $ExpectedExit -or ($output -join "`n") -notmatch $ExpectedText) {
        throw "$Name unexpected result: exit=$exitCode output=$($output -join ' ')"
    }
    Write-Output "PASS $Name"
}

Invoke-GradleCase 'ordinary local diagnostic build guarded' @('help') 0 'PTV_MOBILE_PREFLIGHT=PASS MODE=Build'
Invoke-GradleCase 'debug APK task denied until protected development policy is installed' @(':app:assembleLibreDebug','--dry-run') 1 'PTV Mobile workspace preflight rejected DebugPackage'
Invoke-GradleCase 'explicit configuration cache denied' @('help','--configuration-cache') 1 'configuration cache is disabled'
Invoke-GradleCase 'configuration cache retry remains denied' @('help','--configuration-cache') 1 'configuration cache is disabled'
Invoke-GradleCase 'debug package version override denied' @(':app:assembleLibreDebug','--dry-run','-Ppiggietv.version=9.9.9') 1 'Unapproved Gradle package arguments'
$externalPropertyName = 'ORG_GRADLE_PROJECT_piggietv.version'
$priorExternalProperty = [Environment]::GetEnvironmentVariable($externalPropertyName, 'Process')
try {
    [Environment]::SetEnvironmentVariable($externalPropertyName, '9.9.9', 'Process')
    Invoke-GradleCase 'external Gradle version property denied' @(':app:assembleLibreDebug','--dry-run') 1 'package version differs from the approved source version'
} finally {
    [Environment]::SetEnvironmentVariable($externalPropertyName, $priorExternalProperty, 'Process')
}
Invoke-GradleCase 'debug package JVM override denied' @(':app:assembleLibreDebug','--dry-run','-Duser.country=CA') 1 'Unapproved Gradle package arguments'
Invoke-GradleCase 'debug package task exclusion denied' @(':app:assembleLibreDebug','--dry-run','-x',':app:lintLibreDebug') 1 'Unapproved Gradle package arguments'
$initFixture = Join-Path ([IO.Path]::GetFullPath($env:TEMP)) ("ptv-mobile-init-" + [guid]::NewGuid().ToString('N') + '.gradle')
try {
    [IO.File]::WriteAllText($initFixture, '// disposable init-script fixture')
    Invoke-GradleCase 'debug package init script denied' @(':app:assembleLibreDebug','--dry-run','--init-script',$initFixture) 1 'Unapproved Gradle package arguments'
} finally {
    $tempPrefix = [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
    if (-not $initFixture.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Fixture path escaped TEMP.' }
    if (Test-Path -LiteralPath $initFixture) { Remove-Item -LiteralPath $initFixture -Force }
}

$end = @(git -C $root status --porcelain=v1 -uall)
if (($start -join "`n") -cne ($end -join "`n")) { throw 'Gradle entrypoint tests changed source.' }
Write-Output 'RESULT 9 Gradle entrypoint cases passed'
