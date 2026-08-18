[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version,

    [string]$ExpectedCertificateSha256 = $env:SIGNING_CERTIFICATE_SHA256,

    [string]$OutputDirectory = 'build/public-beta'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Set-Location -LiteralPath $repoRoot

function Normalize-CertificateDigest {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw 'Expected public-beta certificate fingerprint is unavailable.'
    }

    $normalized = ($Value -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
    if ($normalized.Length -ne 64) {
        throw 'Expected public-beta certificate fingerprint must be a SHA-256 digest.'
    }
    return $normalized
}

function Get-SingleSignerDigest {
    param(
        [string]$Output,
        [string]$Pattern,
        [string]$ArtifactName
    )

    $signerMatches = [regex]::Matches($Output, $Pattern)
    if ($signerMatches.Count -eq 0) {
        throw "Unable to read signer certificate from $ArtifactName."
    }
    if ($signerMatches.Count -ne 1) {
        throw "Expected exactly one signer certificate in $ArtifactName."
    }

    return Normalize-CertificateDigest $signerMatches[0].Groups[1].Value
}

function Get-AndroidSdkRoot {
    foreach ($variable in @('ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
        $value = [Environment]::GetEnvironmentVariable($variable)
        if (-not [string]::IsNullOrWhiteSpace($value) -and (Test-Path -LiteralPath $value)) {
            return [IO.Path]::GetFullPath($value)
        }
    }

    $localProperties = Join-Path $repoRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($null -ne $sdkLine) {
            $value = $sdkLine.Substring($sdkLine.IndexOf('=') + 1)
            $value = $value.Replace('\:', ':').Replace('\\', '\')
            if (Test-Path -LiteralPath $value) {
                return [IO.Path]::GetFullPath($value)
            }
        }
    }

    throw 'Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT.'
}

function Invoke-CheckedTool {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$Label
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $outputLines = & $FilePath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $output = ($outputLines | Out-String).Trim()
    if ($exitCode -ne 0) {
        throw "$Label failed with exit code $exitCode.`n$output"
    }
    return $output
}

function Get-JavaToolPath {
    param([string]$Name)

    $executableName = if ([IO.Path]::DirectorySeparatorChar -eq '\') { "$Name.exe" } else { $Name }
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidates += Join-Path $env:JAVA_HOME "bin/$executableName"
    }

    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($null -ne $javaCommand) {
        $candidates += Join-Path (Split-Path -Parent $javaCommand.Source) $executableName
    }

    if ([IO.Path]::DirectorySeparatorChar -eq '\') {
        $candidates += Join-Path $env:ProgramFiles "Android/Android Studio/jbr/bin/$executableName"
    }

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return [IO.Path]::GetFullPath($candidate)
        }
    }

    $gradleUserHome = if ([string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) {
        Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle'
    } else {
        $env:GRADLE_USER_HOME
    }
    $gradleJdks = Join-Path $gradleUserHome 'jdks'
    if (Test-Path -LiteralPath $gradleJdks) {
        $discovered = Get-ChildItem -LiteralPath $gradleJdks -Recurse -File -Filter $executableName |
            Sort-Object FullName -Descending |
            Select-Object -First 1 -ExpandProperty FullName
        if (-not [string]::IsNullOrWhiteSpace($discovered)) {
            return $discovered
        }
    }

    throw "$Name was not found in the configured Java installation."
}

function Get-BundletoolPath {
    $version = '1.18.3'
    $expectedSha256 = 'A099CFA1543F55593BC2ED16A70A7C67FE54B1747BB7301F37FDFD6D91028E29'
    $toolDirectory = Join-Path $repoRoot 'build/tools'
    $toolPath = Join-Path $toolDirectory "bundletool-all-$version.jar"

    if (-not (Test-Path -LiteralPath $toolPath)) {
        New-Item -ItemType Directory -Force -Path $toolDirectory | Out-Null
        $uri = "https://github.com/google/bundletool/releases/download/$version/bundletool-all-$version.jar"
        Invoke-WebRequest -UseBasicParsing -Uri $uri -OutFile $toolPath
    }

    $actualSha256 = (Get-FileHash -LiteralPath $toolPath -Algorithm SHA256).Hash
    if ($actualSha256 -ne $expectedSha256) {
        throw 'bundletool failed its pinned SHA-256 integrity check.'
    }
    return $toolPath
}

function Assert-ReleaseApkIdentity {
    param(
        [IO.FileInfo]$Apk,
        [string]$ApkAnalyzer,
        [string]$ExpectedVersionCode
    )

    $applicationId = Invoke-CheckedTool $ApkAnalyzer @('manifest', 'application-id', $Apk.FullName) 'APK application ID check'
    $versionName = Invoke-CheckedTool $ApkAnalyzer @('manifest', 'version-name', $Apk.FullName) 'APK version-name check'
    $versionCode = Invoke-CheckedTool $ApkAnalyzer @('manifest', 'version-code', $Apk.FullName) 'APK version-code check'
    $debuggable = Invoke-CheckedTool $ApkAnalyzer @('manifest', 'debuggable', $Apk.FullName) 'APK debuggable check'
    $minSdk = Invoke-CheckedTool $ApkAnalyzer @('manifest', 'min-sdk', $Apk.FullName) 'APK minSdk check'
    $targetSdk = Invoke-CheckedTool $ApkAnalyzer @('manifest', 'target-sdk', $Apk.FullName) 'APK targetSdk check'

    if ($applicationId -ne 'com.piggietv.android') {
        throw "Unexpected application ID in $($Apk.Name): $applicationId"
    }
    if ($versionName -ne $Version) {
        throw "Unexpected version name in $($Apk.Name): $versionName"
    }
    if ($versionCode -ne $ExpectedVersionCode) {
        throw "Unexpected version code in $($Apk.Name): $versionCode"
    }
    if ($debuggable -ne 'false') {
        throw "Public-beta APK is debuggable: $($Apk.Name)"
    }
    if ($minSdk -ne '21' -or $targetSdk -ne '36') {
        throw "Unexpected SDK contract in $($Apk.Name): minSdk=$minSdk targetSdk=$targetSdk"
    }
}

function Assert-ReleaseBundleIdentity {
    param(
        [IO.FileInfo]$Bundle,
        [string]$Java,
        [string]$Bundletool,
        [string]$ExpectedVersionCode
    )

    $manifestOutput = Invoke-CheckedTool $Java @(
        '-jar',
        $Bundletool,
        'dump',
        'manifest',
        "--bundle=$($Bundle.FullName)",
        '--module=base'
    ) 'AAB manifest inspection'
    $manifestStart = $manifestOutput.IndexOf('<manifest', [StringComparison]::Ordinal)
    $manifestEnd = $manifestOutput.LastIndexOf('</manifest>', [StringComparison]::Ordinal)
    if ($manifestStart -lt 0 -or $manifestEnd -lt $manifestStart) {
        throw "Unable to read the base manifest from $($Bundle.Name)."
    }
    $manifestLength = $manifestEnd + '</manifest>'.Length - $manifestStart
    [xml]$manifestXml = $manifestOutput.Substring($manifestStart, $manifestLength)
    $manifest = $manifestXml.manifest
    $androidNamespace = 'http://schemas.android.com/apk/res/android'
    $versionName = $manifest.GetAttribute('versionName', $androidNamespace)
    $versionCode = $manifest.GetAttribute('versionCode', $androidNamespace)
    $minSdk = $manifest.'uses-sdk'.GetAttribute('minSdkVersion', $androidNamespace)
    $targetSdk = $manifest.'uses-sdk'.GetAttribute('targetSdkVersion', $androidNamespace)
    $debuggable = $manifest.application.GetAttribute('debuggable', $androidNamespace)

    if ($manifest.package -ne 'com.piggietv.android') {
        throw "Unexpected application ID in $($Bundle.Name): $($manifest.package)"
    }
    if ($versionName -ne $Version) {
        throw "Unexpected version name in $($Bundle.Name): $versionName"
    }
    if ($versionCode -ne $ExpectedVersionCode) {
        throw "Unexpected version code in $($Bundle.Name): $versionCode"
    }
    if ($debuggable -eq 'true') {
        throw "Public-beta AAB is debuggable: $($Bundle.Name)"
    }
    if ($minSdk -ne '21' -or $targetSdk -ne '36') {
        throw "Unexpected SDK contract in $($Bundle.Name): minSdk=$minSdk targetSdk=$targetSdk"
    }
}

$expectedDigest = Normalize-CertificateDigest $ExpectedCertificateSha256
$sdkRoot = Get-AndroidSdkRoot
$toolExtension = if ([IO.Path]::DirectorySeparatorChar -eq '\') { '.bat' } else { '' }

$apkSigner = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    ForEach-Object { Join-Path $_.FullName "apksigner$toolExtension" } |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($apkSigner)) {
    throw 'apksigner was not found in the Android SDK build-tools directory.'
}

$apkAnalyzer = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'cmdline-tools') -Recurse -File -Filter "apkanalyzer$toolExtension" |
    Sort-Object FullName -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if ([string]::IsNullOrWhiteSpace($apkAnalyzer)) {
    throw 'apkanalyzer was not found in the Android SDK command-line tools.'
}

$jarSigner = Get-JavaToolPath 'jarsigner'
$keyTool = Get-JavaToolPath 'keytool'
$java = Get-JavaToolPath 'java'
$bundletool = Get-BundletoolPath

$versionFile = Get-Item -LiteralPath (Join-Path $repoRoot 'app/build/version.txt')
$versionLine = (Get-Content -Raw -LiteralPath $versionFile.FullName).Trim()
$versionPattern = '^v' + [regex]::Escape($Version) + '=(\d+)$'
if ($versionLine -notmatch $versionPattern) {
    throw "Unexpected version manifest: $versionLine"
}
$versionCode = $Matches[1]

$expectedFiles = @(
    [pscustomobject]@{
        Variant = 'libreRelease'
        Format = 'APK'
        Path = "app/build/outputs/apk/libre/release/piggietv-android-v$Version-libre-release.apk"
    },
    [pscustomobject]@{
        Variant = 'proprietaryRelease'
        Format = 'APK'
        Path = "app/build/outputs/apk/proprietary/release/piggietv-android-v$Version-proprietary-release.apk"
    },
    [pscustomobject]@{
        Variant = 'proprietaryRelease'
        Format = 'AAB'
        Path = "app/build/outputs/bundle/proprietaryRelease/piggietv-android-v$Version-proprietary-release.aab"
    }
)

$verifiedArtifacts = @()
foreach ($expectedFile in $expectedFiles) {
    $artifact = Get-Item -LiteralPath (Join-Path $repoRoot $expectedFile.Path)
    if ($artifact.Name -match '(?i)(debug|unsigned)') {
        throw "Debug or unsigned file cannot be a public-beta artifact: $($artifact.Name)"
    }

    if ($expectedFile.Format -eq 'APK') {
        Assert-ReleaseApkIdentity $artifact $apkAnalyzer $versionCode
        $signatureOutput = Invoke-CheckedTool $apkSigner @('verify', '--verbose', '--print-certs', $artifact.FullName) 'APK signature verification'
        $actualDigest = Get-SingleSignerDigest `
            $signatureOutput `
            '(?im)^(?:Signer #\d+|V\d+ Signer):?\s+certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)\s*$' `
            $artifact.Name
    } else {
        Assert-ReleaseBundleIdentity $artifact $java $bundletool $versionCode
        $signatureOutput = Invoke-CheckedTool $jarSigner @(
            '-J-Duser.language=en',
            '-J-Duser.country=US',
            '-verify',
            '-verbose',
            '-certs',
            $artifact.FullName
        ) 'AAB signature verification'
        if ($signatureOutput -notmatch '(?i)jar verified') {
            throw "AAB signature verification did not report success for $($artifact.Name)."
        }
        if ($signatureOutput -match '(?im)^\s*\?\s+= unsigned entry\s*$|^This jar contains unsigned entries\b') {
            throw "AAB contains unsigned entries: $($artifact.Name)."
        }
        $certificateOutput = Invoke-CheckedTool $keyTool @(
            '-J-Duser.language=en',
            '-J-Duser.country=US',
            '-printcert',
            '-jarfile',
            $artifact.FullName
        ) 'AAB certificate inspection'
        $actualDigest = Get-SingleSignerDigest `
            $certificateOutput `
            '(?ims)^Signer #\d+:\s+Certificate #1:.*?^\s*SHA256:\s*([0-9A-Fa-f:]+)\s*$' `
            $artifact.Name
    }

    if ($actualDigest -ne $expectedDigest) {
        throw "Signer certificate mismatch for $($artifact.Name)."
    }

    $verifiedArtifacts += [pscustomobject]@{
        variant = $expectedFile.Variant
        format = $expectedFile.Format
        file = $artifact.Name
        sha256 = (Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash
        signerCertificateSha256 = $actualDigest
        signatureVerified = $true
    }
}

$buildRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'build'))
$outputPath = [IO.Path]::GetFullPath((Join-Path $repoRoot $OutputDirectory))
$allowedPrefix = $buildRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $outputPath.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Candidate output directory must be a child of the repository build directory.'
}
if (Test-Path -LiteralPath $outputPath) {
    Remove-Item -LiteralPath $outputPath -Recurse -Force
}
New-Item -ItemType Directory -Path $outputPath | Out-Null

foreach ($expectedFile in $expectedFiles) {
    Copy-Item -LiteralPath (Join-Path $repoRoot $expectedFile.Path) -Destination $outputPath
}
Copy-Item -LiteralPath $versionFile.FullName -Destination (Join-Path $outputPath 'version.txt')

$sourceCommit = (Invoke-CheckedTool 'git' @('rev-parse', 'HEAD') 'Source commit lookup').Trim()
$trackedChanges = (Invoke-CheckedTool 'git' @('status', '--porcelain', '--untracked-files=all') 'Worktree lookup').Trim()
$manifest = [ordered]@{
    version = $Version
    versionCode = [int]$versionCode
    packageId = 'com.piggietv.android'
    minSdk = 21
    targetSdk = 36
    sourceCommit = $sourceCommit
    worktreeState = if ([string]::IsNullOrWhiteSpace($trackedChanges)) { 'clean' } else { 'dirty' }
    artifacts = $verifiedArtifacts
}
$manifestPath = Join-Path $outputPath 'artifact-manifest.json'
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
$manifest | ConvertTo-Json -Depth 5
