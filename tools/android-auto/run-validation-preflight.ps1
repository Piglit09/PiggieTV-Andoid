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
$checkScript = Join-Path $PSScriptRoot "check-android-auto-env.ps1"
$installScript = Join-Path $PSScriptRoot "install-debug-apk.ps1"

Write-Host "PTV Music Android Auto validation preflight"
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

$adbPath = Get-AdbPath
if ($adbPath) {
    Write-Status "ADB" "PASS" $adbPath
} else {
    Write-Status "ADB" "BLOCKED" "adb was not found on PATH or common SDK paths"
}

$authorizedSerials = @(Get-AuthorizedDeviceSerials -AdbPath $adbPath)
if ($authorizedSerials.Count -eq 0) {
    Write-Status "Authorized device" "BLOCKED" "No authorized adb device is attached"
} else {
    Write-Status "Authorized device" "PASS" ($authorizedSerials -join ", ")
}

if ($Install) {
    if ($authorizedSerials.Count -eq 0) {
        Write-Status "Install APK" "BLOCKED" "Skipping install because no authorized device is attached"
    } else {
        $installArgs = @{
            ApkPath = $latestApk.FullName
        }
        if ($Serial) {
            $installArgs.Serial = $Serial
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
Write-Host "== Next Manual Steps =="
Write-Host "1. Connect a phone with USB debugging enabled and accept the RSA authorization prompt."
Write-Host "2. Install DHU from Android Studio SDK Manager > SDK Tools > Android Auto Desktop Head Unit Emulator."
Write-Host "3. On the phone, enable Android Auto developer mode, Unknown sources, and Start head unit server."
Write-Host "4. Start focused logs before testing:"
Write-Host "   .\tools\android-auto\capture-android-auto-logs.ps1 -Clear"
Write-Host "5. Forward DHU and launch it:"
Write-Host "   & `"$adbPath`" forward tcp:5277 tcp:5277"
Write-Host "   & `"$env:LOCALAPPDATA\Android\Sdk\extras\google\auto\desktop-head-unit.exe`""
Write-Host "6. Follow docs\android-auto-ptv-music-validation.md."
