param(
    [switch] $Install,
    [string] $Serial
)

$ErrorActionPreference = "Continue"

function Get-RepoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

function Get-AdbPath {
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $command) {
        $command = Get-Command adb -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    if ($command) {
        return $command.Source
    }

    $candidates = @()
    if ($env:ANDROID_HOME) {
        $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe")
    }
    if ($env:ANDROID_SDK_ROOT) {
        $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe")
    }
    if ($env:LOCALAPPDATA) {
        $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    }
    if ($env:USERPROFILE) {
        $candidates += (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk\platform-tools\adb.exe")
    }
    $candidates += "C:\Users\Piggie\AppData\Local\Android\Sdk\platform-tools\adb.exe"

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    return $null
}

function Get-AuthorizedDeviceSerials {
    param([string] $AdbPath)

    if (-not $AdbPath) {
        return @()
    }

    $raw = & $AdbPath devices -l
    $serials = @()
    foreach ($line in ($raw | Select-Object -Skip 1)) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        $parts = $line.Trim() -split "\s+"
        if ($parts.Count -ge 2 -and $parts[1] -eq "device") {
            $serials += $parts[0]
        }
    }

    return $serials
}

function Get-LatestDebugApk {
    param([string] $RepoRoot)

    $apkDirectory = Join-Path $RepoRoot "app\build\outputs\apk\proprietary\debug"
    if (-not (Test-Path $apkDirectory)) {
        return $null
    }

    return Get-ChildItem -Path $apkDirectory -Filter *.apk -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

function Get-AssetCount {
    param(
        [string] $Root,
        [string] $SubDirectory,
        [string[]] $Extensions
    )

    $directory = Join-Path $Root $SubDirectory
    if (-not (Test-Path $directory)) {
        return 0
    }

    return @(Get-ChildItem -Path $directory -File |
        Where-Object { $Extensions -contains $_.Extension.ToLowerInvariant() }).Count
}

function Write-Status {
    param(
        [string] $Name,
        [string] $Status,
        [string] $Detail
    )

    if ($Detail) {
        Write-Host "[$Status] $Name - $Detail"
    } else {
        Write-Host "[$Status] $Name"
    }
}

$repoRoot = Get-RepoRoot
$gradlew = Join-Path $repoRoot "gradlew.bat"
$checkScript = Join-Path $PSScriptRoot "check-books-env.ps1"
$installScript = Join-Path $PSScriptRoot "install-debug-apk.ps1"
$generationScript = Join-Path $PSScriptRoot "generate-books-test-assets.ps1"
$validationDoc = Join-Path $repoRoot "docs\native-books-runtime-validation.md"
$resultsTemplate = Join-Path $repoRoot "docs\native-books-validation-results.md"
$sampleFilesDirectory = Join-Path $repoRoot "test-assets\books"

Write-Host "PTV Books runtime validation preflight"
Write-Host "Repo root: $repoRoot"
Write-Host ""

& $checkScript

Write-Host ""
Write-Host "== Build =="
Push-Location $repoRoot
try {
    & $gradlew :app:assembleProprietaryDebug
    if ($LASTEXITCODE -ne 0) {
        Write-Status "Assemble proprietary debug" "FAIL" "Gradle exited with $LASTEXITCODE"
        exit $LASTEXITCODE
    }
    Write-Status "Assemble proprietary debug" "PASS" $null
} finally {
    Pop-Location
}

$latestApk = Get-LatestDebugApk -RepoRoot $repoRoot
if ($latestApk) {
    Write-Status "Latest proprietary debug APK" "PASS" $latestApk.FullName
} else {
    Write-Status "Latest proprietary debug APK" "FAIL" "No APK found under app\build\outputs\apk\proprietary\debug"
    exit 1
}

if (Test-Path $validationDoc) {
    Write-Status "Books validation doc" "PASS" $validationDoc
} else {
    Write-Status "Books validation doc" "FAIL" "Missing docs\native-books-runtime-validation.md"
    exit 1
}

if (Test-Path $resultsTemplate) {
    Write-Status "Books results template" "PASS" $resultsTemplate
} else {
    Write-Status "Books results template" "BLOCKED" "Missing docs\native-books-validation-results.md"
}

if (Test-Path $generationScript) {
    Write-Status "Books asset generation script" "PASS" $generationScript
} else {
    Write-Status "Books asset generation script" "BLOCKED" "Missing tools\books\generate-books-test-assets.ps1"
}

if (Test-Path $sampleFilesDirectory) {
    Write-Status "Books sample asset directory" "PASS" $sampleFilesDirectory
    Write-Status "EPUB sample count" "INFO" "$(Get-AssetCount -Root $sampleFilesDirectory -SubDirectory "epub" -Extensions @(".epub"))"
    Write-Status "PDF sample count" "INFO" "$(Get-AssetCount -Root $sampleFilesDirectory -SubDirectory "pdf" -Extensions @(".pdf"))"
    Write-Status "CBZ sample count" "INFO" "$(Get-AssetCount -Root $sampleFilesDirectory -SubDirectory "cbz" -Extensions @(".cbz"))"
    Write-Status "TXT sample count" "INFO" "$(Get-AssetCount -Root $sampleFilesDirectory -SubDirectory "txt" -Extensions @(".txt"))"
    Write-Status "Unsupported sample count" "INFO" "$(Get-AssetCount -Root $sampleFilesDirectory -SubDirectory "unsupported" -Extensions @(".cbr", ".mobi", ".azw", ".azw3", ".md", ".html", ".booktest"))"
} else {
    Write-Status "Books sample asset directory" "BLOCKED" "Run .\tools\books\generate-books-test-assets.ps1"
}

$adbPath = Get-AdbPath
if ($adbPath) {
    Write-Status "ADB" "PASS" $adbPath
} else {
    Write-Status "ADB" "BLOCKED" "adb was not found on PATH or common SDK paths"
}

$authorizedSerials = @(Get-AuthorizedDeviceSerials -AdbPath $adbPath)
if ($authorizedSerials.Count -eq 0) {
    Write-Status "Authorized device" "BLOCKED" "No authorized adb device is attached; runtime Books validation is blocked, not failed"
} else {
    Write-Status "Authorized device" "PASS" ($authorizedSerials -join ", ")
}

if ($Install) {
    if ($authorizedSerials.Count -eq 0) {
        Write-Status "Install APK" "BLOCKED" "Skipping install because no authorized device is attached"
    } else {
        $installArgs = @("-ApkPath", $latestApk.FullName)
        if ($Serial) {
            $installArgs += @("-Serial", $Serial)
        }
        & $installScript @installArgs
        if ($LASTEXITCODE -ne 0) {
            Write-Status "Install APK" "FAIL" "Installer exited with $LASTEXITCODE"
            exit $LASTEXITCODE
        }
        Write-Status "Install APK" "PASS" $null
    }
} else {
    Write-Status "Install APK" "SKIPPED" "Run with -Install after connecting an authorized device"
}

Write-Host ""
Write-Host "== Next Manual Books Validation Steps =="
Write-Host "1. Generate non-copyrighted sample files if needed:"
Write-Host "   .\tools\books\generate-books-test-assets.ps1"
Write-Host "2. Or put public-domain/self-created test files under test-assets\books or make them available through the configured OPDS/Calibre source."
Write-Host "3. Connect a phone/tablet/TV device with USB debugging enabled and accept the RSA authorization prompt."
Write-Host "4. Install the newest proprietary debug APK:"
Write-Host "   .\tools\books\install-debug-apk.ps1"
Write-Host "   .\tools\books\install-debug-apk.ps1 -Serial <device-serial>"
Write-Host "5. Start focused logs before opening Books:"
Write-Host "   .\tools\books\capture-books-logs.ps1 -Clear"
Write-Host "   .\tools\books\capture-books-logs.ps1 -Clear -DurationSeconds 300"
Write-Host "6. Follow docs\native-books-runtime-validation.md and record results in docs\native-books-validation-results.md."
Write-Host "7. Mark each row PASS, FAIL, or BLOCKED. BLOCKED means missing sample file, missing device, or setup not available."
