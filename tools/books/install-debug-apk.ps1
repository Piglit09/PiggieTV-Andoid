param(
    [string] $ApkPath,
    [string] $AdbPath,
    [string] $Serial
)

$ErrorActionPreference = "Stop"

function Get-RepoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

function Resolve-AdbPath {
    param([string] $ExplicitAdbPath)

    if ($ExplicitAdbPath) {
        if (Test-Path $ExplicitAdbPath) {
            return (Resolve-Path $ExplicitAdbPath).Path
        }
        throw "Explicit adb path was not found: $ExplicitAdbPath"
    }

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

    throw "adb was not found on PATH or in common Android SDK locations."
}

function Resolve-ApkPath {
    param(
        [string] $RepoRoot,
        [string] $ExplicitApkPath
    )

    if ($ExplicitApkPath) {
        $resolved = if ([System.IO.Path]::IsPathRooted($ExplicitApkPath)) {
            $ExplicitApkPath
        } else {
            Join-Path $RepoRoot $ExplicitApkPath
        }
        if (Test-Path $resolved) {
            return (Resolve-Path $resolved).Path
        }
        throw "Explicit APK path was not found: $ExplicitApkPath"
    }

    $apkDirectory = Join-Path $RepoRoot "app\build\outputs\apk\proprietary\debug"
    if (-not (Test-Path $apkDirectory)) {
        throw "APK directory was not found: $apkDirectory"
    }

    $apk = Get-ChildItem -Path $apkDirectory -Filter *.apk -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $apk) {
        throw "No proprietary debug APK was found under $apkDirectory"
    }

    return $apk.FullName
}

function Get-AuthorizedDevices {
    param([string] $ResolvedAdbPath)

    $raw = & $ResolvedAdbPath devices -l
    $devices = @()
    foreach ($line in ($raw | Select-Object -Skip 1)) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        $parts = $line.Trim() -split "\s+"
        if ($parts.Count -ge 2 -and $parts[1] -eq "device") {
            $devices += [pscustomobject]@{
                Serial = $parts[0]
                Detail = $line.Trim()
            }
        }
    }

    return [pscustomobject]@{
        Raw = $raw
        Devices = $devices
    }
}

$repoRoot = Get-RepoRoot
$resolvedAdbPath = Resolve-AdbPath -ExplicitAdbPath $AdbPath
$resolvedApkPath = Resolve-ApkPath -RepoRoot $repoRoot -ExplicitApkPath $ApkPath
$deviceResult = Get-AuthorizedDevices -ResolvedAdbPath $resolvedAdbPath
$authorizedDevices = @($deviceResult.Devices)

Write-Host "adb: $resolvedAdbPath"
Write-Host "APK: $resolvedApkPath"

if ($authorizedDevices.Count -eq 0) {
    Write-Host ""
    Write-Host "adb devices -l:"
    $deviceResult.Raw | ForEach-Object { Write-Host $_ }
    throw "No authorized adb device is attached. Connect a phone/emulator, enable USB debugging, and accept the authorization prompt."
}

if ($Serial) {
    $matchingDevice = $authorizedDevices | Where-Object { $_.Serial -eq $Serial } | Select-Object -First 1
    if (-not $matchingDevice) {
        throw "Requested adb device '$Serial' is not authorized or not attached."
    }
} elseif ($authorizedDevices.Count -gt 1) {
    Write-Host ""
    Write-Host "Authorized devices:"
    $authorizedDevices | ForEach-Object { Write-Host "  $($_.Detail)" }
    throw "More than one authorized device is attached. Re-run with -Serial <device-serial>."
} else {
    $Serial = $authorizedDevices[0].Serial
}

Write-Host "Installing Books validation build to device: $Serial"
& $resolvedAdbPath -s $Serial install -r $resolvedApkPath
if ($LASTEXITCODE -ne 0) {
    throw "adb install failed with exit code $LASTEXITCODE"
}

Write-Host "Install complete."
