param(
    [string] $Serial,
    [string] $PackageName
)

$ErrorActionPreference = "Continue"

function Get-RepoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

function Get-AdbPath {
    $adbOnPath = Get-Command adb.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $adbOnPath) {
        $adbOnPath = Get-Command adb -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    if ($adbOnPath) {
        return $adbOnPath.Source
    }

    $candidates = @()
    foreach ($root in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if (-not [string]::IsNullOrWhiteSpace($root)) {
            $candidates += (Join-Path $root "platform-tools\adb.exe")
        }
    }
    if ($env:LOCALAPPDATA) {
        $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    }
    $candidates += "C:\Users\Piggie\AppData\Local\Android\Sdk\platform-tools\adb.exe"

    return $candidates |
        Where-Object { $_ -and (Test-Path $_) } |
        Select-Object -First 1
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

    return $devices
}

function Invoke-Adb {
    param(
        [string] $AdbPath,
        [string] $DeviceSerial,
        [string[]] $Arguments
    )

    if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
        return & $AdbPath @Arguments 2>&1
    }

    return & $AdbPath -s $DeviceSerial @Arguments 2>&1
}

function Invoke-AdbShell {
    param(
        [string] $AdbPath,
        [string] $DeviceSerial,
        [string] $Command
    )

    return Invoke-Adb -AdbPath $AdbPath -DeviceSerial $DeviceSerial -Arguments @("shell", $Command)
}

function Find-PackageName {
    param(
        [string] $AdbPath,
        [string] $DeviceSerial,
        [string] $ExplicitPackageName
    )

    if (-not [string]::IsNullOrWhiteSpace($ExplicitPackageName)) {
        return $ExplicitPackageName
    }

    $packages = Invoke-AdbShell -AdbPath $AdbPath -DeviceSerial $DeviceSerial -Command "pm list packages" |
        ForEach-Object { $_.ToString().Trim() -replace "^package:", "" }

    $preferred = @(
        "com.piggietv.android.debug",
        "com.piggietv.android",
        "org.jellyfin.mobile.debug",
        "org.jellyfin.mobile"
    )

    foreach ($candidate in $preferred) {
        if ($packages -contains $candidate) {
            return $candidate
        }
    }

    return $packages |
        Where-Object { $_ -match "piggietv|jellyfin\.mobile" } |
        Select-Object -First 1
}

function Test-Text {
    param(
        [string] $Text,
        [string] $Pattern
    )

    return $Text -match $Pattern
}

function Write-Check {
    param(
        [string] $Name,
        [bool] $Passed,
        [string] $Detail = ""
    )

    $status = if ($Passed) { "PASS" } else { "FAIL" }
    $line = if ([string]::IsNullOrWhiteSpace($Detail)) {
        "[$status] $Name"
    } else {
        "[$status] $Name - $Detail"
    }
    Write-Host $line
    return $line
}

function Add-Section {
    param(
        [System.Collections.Generic.List[string]] $Lines,
        [string] $Title,
        [object[]] $Content
    )

    $Lines.Add("")
    $Lines.Add("== $Title ==")
    foreach ($line in $Content) {
        $Lines.Add($line.ToString())
    }
}

$repoRoot = Get-RepoRoot
$logDir = Join-Path $repoRoot "logs\android-auto"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logPath = Join-Path $logDir "discovery-$timestamp.txt"
$report = [System.Collections.Generic.List[string]]::new()

$adbPath = Get-AdbPath
$report.Add("PTV Android Auto discovery diagnostic")
$report.Add("Timestamp: $(Get-Date -Format o)")
$report.Add("Repo root: $repoRoot")
$report.Add("Selected adb: $adbPath")

if (-not $adbPath) {
    $report.Add("[FAIL] adb not found")
    $report | Set-Content -Path $logPath -Encoding UTF8
    Write-Host "FAIL: adb not found."
    Write-Host "Wrote diagnostic log: $logPath"
    exit 1
}

$devices = @(Get-AdbDevices -AdbPath $adbPath)
$authorizedDevices = @($devices | Where-Object { $_.Authorized })
Add-Section -Lines $report -Title "ADB Devices" -Content @($devices | ForEach-Object { $_.Detail })

if ($authorizedDevices.Count -eq 0) {
    $report.Add("[FAIL] no authorized adb device attached")
    $report | Set-Content -Path $logPath -Encoding UTF8
    Write-Host "FAIL: no authorized adb device attached."
    Write-Host "Wrote diagnostic log: $logPath"
    exit 1
}

$device = if ([string]::IsNullOrWhiteSpace($Serial)) {
    $authorizedDevices[0]
} else {
    $authorizedDevices | Where-Object { $_.Serial -eq $Serial } | Select-Object -First 1
}

if (-not $device) {
    $report.Add("[FAIL] requested device serial not authorized: $Serial")
    $report | Set-Content -Path $logPath -Encoding UTF8
    Write-Host "FAIL: requested device serial not authorized: $Serial"
    Write-Host "Wrote diagnostic log: $logPath"
    exit 1
}

$detectedPackageName = Find-PackageName -AdbPath $adbPath -DeviceSerial $device.Serial -ExplicitPackageName $PackageName
$report.Add("Selected device: $($device.Serial)")
$report.Add("Detected package: $detectedPackageName")

if ([string]::IsNullOrWhiteSpace($detectedPackageName)) {
    $report.Add("[FAIL] PiggieTV/Jellyfin package not installed")
    $report | Set-Content -Path $logPath -Encoding UTF8
    Write-Host "FAIL: PiggieTV/Jellyfin package not installed."
    Write-Host "Wrote diagnostic log: $logPath"
    exit 1
}

$packageMatches = Invoke-AdbShell -AdbPath $adbPath -DeviceSerial $device.Serial -Command "pm list packages" |
    Where-Object { $_.ToString() -match "piggie|jellyfin|ptv" }
$packageDump = Invoke-AdbShell -AdbPath $adbPath -DeviceSerial $device.Serial -Command "dumpsys package $detectedPackageName"
$libraryResolve = Invoke-AdbShell -AdbPath $adbPath -DeviceSerial $device.Serial -Command "cmd package resolve-service --brief -a androidx.media3.session.MediaLibraryService $detectedPackageName"
$browserResolve = Invoke-AdbShell -AdbPath $adbPath -DeviceSerial $device.Serial -Command "cmd package resolve-service --brief -a android.media.browse.MediaBrowserService $detectedPackageName"
$libraryQuery = Invoke-AdbShell -AdbPath $adbPath -DeviceSerial $device.Serial -Command "cmd package query-services --brief -a androidx.media3.session.MediaLibraryService $detectedPackageName"
$sessionQuery = Invoke-AdbShell -AdbPath $adbPath -DeviceSerial $device.Serial -Command "cmd package query-services --brief -a androidx.media3.session.MediaSessionService $detectedPackageName"
$browserQuery = Invoke-AdbShell -AdbPath $adbPath -DeviceSerial $device.Serial -Command "cmd package query-services --brief -a android.media.browse.MediaBrowserService $detectedPackageName"
$mediaSessionDump = Invoke-AdbShell -AdbPath $adbPath -DeviceSerial $device.Serial -Command "dumpsys media_session"
$gearheadPackages = Invoke-AdbShell -AdbPath $adbPath -DeviceSerial $device.Serial -Command "pm list packages | grep -i 'projection.gearhead\|android.auto'"
$autoSettings = Invoke-AdbShell -AdbPath $adbPath -DeviceSerial $device.Serial -Command "settings list secure | grep -i 'gearhead\|android_auto\|android auto\|unknown'"
$mergedManifestPath = Join-Path $repoRoot "app\build\intermediates\merged_manifests\proprietaryDebug\processProprietaryDebugManifest\AndroidManifest.xml"
$mergedManifest = if (Test-Path $mergedManifestPath) {
    Get-Content -Path $mergedManifestPath -Raw
} else {
    ""
}

$packageText = $packageDump | Out-String
$mediaSessionText = $mediaSessionDump | Out-String
$settingsText = $autoSettings | Out-String
$libraryServiceText = (($libraryResolve | Out-String) + "`n" + ($libraryQuery | Out-String))
$sessionServiceText = $sessionQuery | Out-String
$browserServiceText = (($browserResolve | Out-String) + "`n" + ($browserQuery | Out-String))
$manifestText = if ([string]::IsNullOrWhiteSpace($mergedManifest)) { $packageText } else { $mergedManifest }

Add-Section -Lines $report -Title "pm list packages | findstr /i `"piggie jellyfin ptv`"" -Content $packageMatches
Add-Section -Lines $report -Title "dumpsys package $detectedPackageName" -Content $packageDump
Add-Section -Lines $report -Title "resolve MediaLibraryService" -Content $libraryResolve
Add-Section -Lines $report -Title "resolve MediaBrowserService" -Content $browserResolve
Add-Section -Lines $report -Title "query MediaLibraryService fallback" -Content $libraryQuery
Add-Section -Lines $report -Title "query MediaSessionService fallback" -Content $sessionQuery
Add-Section -Lines $report -Title "query MediaBrowserService fallback" -Content $browserQuery
if (-not [string]::IsNullOrWhiteSpace($mergedManifest)) {
    Add-Section -Lines $report -Title "local merged proprietary debug manifest" -Content @($mergedManifestPath)
}
Add-Section -Lines $report -Title "Android Auto packages" -Content $gearheadPackages
Add-Section -Lines $report -Title "Android Auto / unknown source settings probe" -Content $autoSettings
Add-Section -Lines $report -Title "dumpsys media_session" -Content $mediaSessionDump

$checks = [System.Collections.Generic.List[string]]::new()
$checks.Add((Write-Check "package installed" (Test-Text $packageText "Package \[$([regex]::Escape($detectedPackageName))\]|userId=") $detectedPackageName))
$checks.Add((Write-Check "PtvMusicService declared" (Test-Text $packageText "PtvMusicService")))
$checks.Add((Write-Check "MediaLibraryService action resolves" (Test-Text $libraryServiceText "PtvMusicService|$([regex]::Escape($detectedPackageName))/.+PtvMusicService")))
$checks.Add((Write-Check "MediaSessionService action resolves" (Test-Text $sessionServiceText "PtvMusicService|$([regex]::Escape($detectedPackageName))/.+PtvMusicService")))
$checks.Add((Write-Check "MediaBrowserService action resolves" (Test-Text $browserServiceText "PtvMusicService|$([regex]::Escape($detectedPackageName))/.+PtvMusicService")))
$checks.Add((Write-Check "automotive metadata present" (Test-Text $manifestText "com.google.android.gms.car.application[\s\S]*automotive_app_desc|automotive_app_desc[\s\S]*com.google.android.gms.car.application")))
$checks.Add((Write-Check "foreground media playback type" (Test-Text $manifestText "PtvMusicService[\s\S]*foregroundServiceType=`"mediaPlayback`"|foregroundServiceType=`"mediaPlayback`"[\s\S]*PtvMusicService|FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK")))
$checks.Add((Write-Check "exported service" (Test-Text $manifestText "PtvMusicService[\s\S]*exported=`"true`"|exported=true[\s\S]*PtvMusicService")))
$checks.Add((Write-Check "media session visible" (Test-Text $mediaSessionText "PtvMusicService|ptv_music|$([regex]::Escape($detectedPackageName))")))
$checks.Add((Write-Check "notification permission granted" (Test-Text $packageText "android.permission.POST_NOTIFICATIONS: granted=true") "grant this permission before Android Auto testing"))
$checks.Add((Write-Check "Android Auto package present" (Test-Text ($gearheadPackages | Out-String) "projection.gearhead|android.auto") "install/enable Android Auto if FAIL"))

$unknownSourceKnown = -not [string]::IsNullOrWhiteSpace($settingsText)
$unknownSourceEnabled = $settingsText -match "(unknown|source).*(1|true|enabled)|(1|true|enabled).*(unknown|source)"
if ($unknownSourceKnown) {
    $checks.Add((Write-Check "Android Auto unknown sources probe" $unknownSourceEnabled "settings matched; review log if FAIL"))
} else {
    $checks.Add("[WARN] Android Auto unknown sources probe - Android does not expose a stable adb setting on this device; enable Android Auto developer mode + Unknown sources manually for debug APKs.")
    Write-Host "[WARN] Android Auto unknown sources probe - enable Android Auto developer mode + Unknown sources manually for debug APKs."
}

Add-Section -Lines $report -Title "PASS/FAIL Summary" -Content $checks
$report | Set-Content -Path $logPath -Encoding UTF8

Write-Host ""
Write-Host "Wrote diagnostic log: $logPath"
Write-Host "Detected package: $detectedPackageName"

$failedRequiredChecks = @($checks | Where-Object { $_.StartsWith("[FAIL]") })
if ($failedRequiredChecks.Count -gt 0) {
    Write-Host "Android Auto discovery diagnostic: FAIL"
    exit 1
}

Write-Host "Android Auto discovery diagnostic: PASS"
