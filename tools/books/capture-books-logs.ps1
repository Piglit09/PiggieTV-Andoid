param(
    [string] $AdbPath,
    [string] $Serial,
    [switch] $Clear,
    [int] $DurationSeconds = 0
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

function Resolve-DeviceSerial {
    param(
        [string] $ResolvedAdbPath,
        [string] $RequestedSerial
    )

    $raw = & $ResolvedAdbPath devices -l
    $devices = @()
    foreach ($line in ($raw | Select-Object -Skip 1)) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        $parts = $line.Trim() -split "\s+"
        if ($parts.Count -ge 2 -and $parts[1] -eq "device") {
            $devices += $parts[0]
        }
    }

    if ($RequestedSerial) {
        if ($devices -contains $RequestedSerial) {
            return $RequestedSerial
        }
        throw "Requested adb device '$RequestedSerial' is not authorized or not attached."
    }

    $authorizedDevices = @($devices)

    if ($authorizedDevices.Count -eq 0) {
        throw "No authorized adb device is attached. Cannot capture Books logs."
    }
    if ($authorizedDevices.Count -gt 1) {
        throw "More than one authorized device is attached. Re-run with -Serial <device-serial>."
    }

    return $authorizedDevices[0]
}

$repoRoot = Get-RepoRoot
$resolvedAdbPath = Resolve-AdbPath -ExplicitAdbPath $AdbPath
$resolvedSerial = Resolve-DeviceSerial -ResolvedAdbPath $resolvedAdbPath -RequestedSerial $Serial
$logDirectory = Join-Path $repoRoot "logs\books"
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logFile = Join-Path $logDirectory "books-$timestamp.log"
$pattern = "LibraryReader|LibraryEpubReader|LibraryBitmapCache|LibraryRepository|OpdsClient|PTV Books|OutOfMemory|Bitmap|PdfRenderer|CBZ|EPUB"
$adbDeviceArgs = @("-s", $resolvedSerial)

Write-Host "adb: $resolvedAdbPath"
Write-Host "device: $resolvedSerial"
Write-Host "log file: $logFile"
Write-Host "filter: $pattern"

if ($Clear) {
    & $resolvedAdbPath @adbDeviceArgs logcat -c
}

if ($DurationSeconds -gt 0) {
    $rawFile = Join-Path $logDirectory "books-$timestamp.raw.log"
    $errorFile = Join-Path $logDirectory "books-$timestamp.err.log"
    $process = Start-Process -FilePath $resolvedAdbPath `
        -ArgumentList @($adbDeviceArgs + "logcat") `
        -NoNewWindow `
        -PassThru `
        -RedirectStandardOutput $rawFile `
        -RedirectStandardError $errorFile
    Start-Sleep -Seconds $DurationSeconds
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
    }
    Select-String -Path $rawFile -Pattern $pattern | ForEach-Object { $_.Line } | Set-Content -Path $logFile
    Write-Host "Captured filtered Books logs for $DurationSeconds seconds."
    Write-Host "Filtered log: $logFile"
    Write-Host "Raw log: $rawFile"
} else {
    Write-Host "Capturing live filtered Books logs. Press Ctrl+C to stop."
    & $resolvedAdbPath @adbDeviceArgs logcat |
        Select-String -Pattern $pattern |
        ForEach-Object {
            $_.Line | Tee-Object -FilePath $logFile -Append
        }
}
