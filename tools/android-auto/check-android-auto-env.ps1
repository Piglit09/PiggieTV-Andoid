param(
    [string] $PackageName = "com.piggietv.android.debug"
)

$ErrorActionPreference = "Continue"

function Get-RepoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

function Get-AndroidSdkRoots {
    $roots = @()
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path $candidate)) {
            $roots += (Resolve-Path $candidate).Path
        }
    }

    $commonCandidates = @("C:\Android\Sdk")
    if ($env:LOCALAPPDATA) {
        $commonCandidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    }
    if ($env:USERPROFILE) {
        $commonCandidates += (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk")
    }

    foreach ($candidate in $commonCandidates) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path $candidate)) {
            $roots += (Resolve-Path $candidate).Path
        }
    }

    return @($roots | Select-Object -Unique)
}

function Get-AdbOnPath {
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $command) {
        $command = Get-Command adb -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    if ($command) {
        return $command.Source
    }
    return $null
}

function Get-AdbCandidates {
    param([string[]] $SdkRoots)

    $candidates = @()
    $adbOnPath = Get-AdbOnPath
    if ($adbOnPath) {
        $candidates += $adbOnPath
    }
    foreach ($root in $SdkRoots) {
        $candidates += (Join-Path $root "platform-tools\adb.exe")
    }
    if ($env:LOCALAPPDATA) {
        $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    }

    return @($candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique)
}

function Get-DhuCandidates {
    param([string[]] $SdkRoots)

    $candidates = @()
    foreach ($root in $SdkRoots) {
        $candidates += (Join-Path $root "extras\google\auto\desktop-head-unit.exe")
        $candidates += Get-ChildItem -Path $root -Recurse -Filter desktop-head-unit.exe -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty FullName
    }

    return @($candidates | Where-Object { $_ } | Select-Object -Unique)
}

function Get-AdbDevices {
    param([string] $AdbPath)

    $raw = & $AdbPath devices -l 2>&1
    $devices = @()
    foreach ($line in ($raw | Select-Object -Skip 1)) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }

        $parts = $line.Trim() -split "\s+"
        if ($parts.Count -lt 2) {
            continue
        }

        $devices += [pscustomobject]@{
            Serial = $parts[0]
            State = $parts[1]
            Authorized = $parts[1] -eq "device"
            Detail = $line.Trim()
        }
    }

    return [pscustomobject]@{
        Raw = $raw
        Devices = $devices
    }
}

function Write-Section {
    param([string] $Title)
    Write-Host ""
    Write-Host "== $Title =="
}

function Write-Value {
    param([string] $Name, [object] $Value)
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace([string] $Value)) {
        Write-Host "${Name}: <not found>"
    } else {
        Write-Host "${Name}: $Value"
    }
}

$repoRoot = Get-RepoRoot
$sdkRoots = @(Get-AndroidSdkRoots)
$adbOnPath = Get-AdbOnPath
$adbCandidates = @(Get-AdbCandidates -SdkRoots $sdkRoots)
$adbPath = if ($adbOnPath) { $adbOnPath } elseif ($adbCandidates.Count -gt 0) { $adbCandidates[0] } else { $null }
$dhuCandidates = @(Get-DhuCandidates -SdkRoots $sdkRoots)
$dhuFound = @($dhuCandidates | Where-Object { Test-Path $_ })
$deviceResult = $null
$apkDirectory = Join-Path $repoRoot "app\build\outputs\apk\proprietary\debug"
$apkCandidates = @()
if (Test-Path $apkDirectory) {
    $apkCandidates = Get-ChildItem -Path $apkDirectory -Filter *.apk -File |
        Sort-Object LastWriteTime -Descending
}

Write-Section "Repository"
Write-Value "Repo root" $repoRoot

Write-Section "Android SDK"
if ($sdkRoots.Count -eq 0) {
    Write-Host "Android SDK location: <not found>"
} else {
    foreach ($root in $sdkRoots) {
        Write-Host "Android SDK location: $root"
    }
}

Write-Section "ADB"
Write-Value "adb on PATH" $(if ($adbOnPath) { "YES ($adbOnPath)" } else { "NO" })
Write-Value "Selected adb" $adbPath
if ($adbCandidates.Count -gt 0) {
    foreach ($candidate in $adbCandidates) {
        Write-Host "adb candidate: $candidate"
    }
}

if ($adbPath) {
    Write-Host ""
    & $adbPath version
    $deviceResult = Get-AdbDevices -AdbPath $adbPath
    Write-Host ""
    Write-Host "Connected adb devices:"
    if ($deviceResult.Devices.Count -eq 0) {
        Write-Host "  <none>"
    } else {
        foreach ($device in $deviceResult.Devices) {
            $status = if ($device.Authorized) { "AUTHORIZED" } else { "NOT AUTHORIZED / $($device.State)" }
            Write-Host "  $($device.Serial) - $status - $($device.Detail)"
        }
    }
}

Write-Section "Android Auto Discoverability"
Write-Value "Package checked" $PackageName
if (-not $adbPath) {
    Write-Host "Android Auto dumpsys check: BLOCKED - adb not found."
} elseif ($null -eq $deviceResult -or @($deviceResult.Devices | Where-Object { $_.Authorized }).Count -eq 0) {
    Write-Host "Android Auto dumpsys check: BLOCKED - no authorized device is attached."
} else {
    foreach ($device in @($deviceResult.Devices | Where-Object { $_.Authorized })) {
        Write-Host ""
        Write-Host "Device $($device.Serial):"
        $packageDump = & $adbPath -s $device.Serial shell dumpsys package $PackageName 2>&1
        $packageText = ($packageDump | Out-String)
        if ($packageText -match "Unable to find package|not found|Can't find package") {
            Write-Host "  Package is not installed: $PackageName"
            continue
        }

        $matches = $packageDump | Select-String -Pattern "PtvMusicService|MediaLibraryService|MediaBrowserService|com.google.android.gms.car.application|automotive|mediaPlayback" -CaseSensitive:$false
        if ($matches) {
            foreach ($match in $matches) {
                Write-Host "  $($match.Line.Trim())"
            }
        } else {
            Write-Host "  No Android Auto media service metadata matched. Re-check manifest merge and installed package."
        }
    }
}

Write-Section "Desktop Head Unit"
if ($dhuCandidates.Count -eq 0) {
    Write-Host "DHU location candidates: <none>"
} else {
    foreach ($candidate in $dhuCandidates) {
        $status = if (Test-Path $candidate) { "FOUND" } else { "missing" }
        Write-Host "DHU candidate [$status]: $candidate"
    }
}
Write-Value "desktop-head-unit.exe exists" $(if ($dhuFound.Count -gt 0) { "YES ($($dhuFound[0]))" } else { "NO" })

Write-Section "APK"
Write-Value "Proprietary debug APK directory" $apkDirectory
if ($apkCandidates.Count -eq 0) {
    Write-Host "Generated APK candidates: <none>"
} else {
    foreach ($apk in $apkCandidates) {
        Write-Host "Generated APK candidate: $($apk.FullName) ($($apk.LastWriteTime))"
    }
    Write-Host "Latest APK: $($apkCandidates[0].FullName)"
}

Write-Section "Java And Gradle"
$javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $javaCommand) {
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue | Select-Object -First 1
}
if ($javaCommand) {
    Write-Host "Java: $($javaCommand.Source)"
    $javaVersion = & $javaCommand.Source -version 2>&1 | Select-Object -First 1
    Write-Host "Java version: $javaVersion"
} else {
    Write-Host "Java: <not found>"
}
Write-Value "Gradle wrapper" $(Join-Path $repoRoot "gradlew.bat")

Write-Host ""
Write-Host "Environment check complete."
