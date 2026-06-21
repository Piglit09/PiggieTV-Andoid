param()

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
    $commonCandidates += "C:\Users\Piggie\AppData\Local\Android\Sdk"

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
    if ($env:USERPROFILE) {
        $candidates += (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk\platform-tools\adb.exe")
    }
    $candidates += "C:\Users\Piggie\AppData\Local\Android\Sdk\platform-tools\adb.exe"

    return @($candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique)
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

$repoRoot = Get-RepoRoot
$sdkRoots = @(Get-AndroidSdkRoots)
$adbOnPath = Get-AdbOnPath
$adbCandidates = @(Get-AdbCandidates -SdkRoots $sdkRoots)
$adbPath = if ($adbOnPath) { $adbOnPath } elseif ($adbCandidates.Count -gt 0) { $adbCandidates[0] } else { $null }
$apkDirectory = Join-Path $repoRoot "app\build\outputs\apk\proprietary\debug"
$apkCandidates = @()
if (Test-Path $apkDirectory) {
    $apkCandidates = Get-ChildItem -Path $apkDirectory -Filter *.apk -File |
        Sort-Object LastWriteTime -Descending
}
$validationDoc = Join-Path $repoRoot "docs\native-books-runtime-validation.md"
$sampleFilesDirectory = Join-Path $repoRoot "test-assets\books"
$generationScript = Join-Path $repoRoot "tools\books\generate-books-test-assets.ps1"

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

Write-Section "Books Validation"
Write-Value "Runtime validation doc" $(if (Test-Path $validationDoc) { "YES ($validationDoc)" } else { "NO ($validationDoc)" })
Write-Value "Sample test files directory" $(if (Test-Path $sampleFilesDirectory) { "YES ($sampleFilesDirectory)" } else { "NO ($sampleFilesDirectory)" })
Write-Value "Generation script" $(if (Test-Path $generationScript) { "YES ($generationScript)" } else { "NO ($generationScript)" })
if (Test-Path $sampleFilesDirectory) {
    Write-Host "EPUB files: $(Get-AssetCount -Root $sampleFilesDirectory -SubDirectory "epub" -Extensions @(".epub"))"
    Write-Host "PDF files: $(Get-AssetCount -Root $sampleFilesDirectory -SubDirectory "pdf" -Extensions @(".pdf"))"
    Write-Host "CBZ files: $(Get-AssetCount -Root $sampleFilesDirectory -SubDirectory "cbz" -Extensions @(".cbz"))"
    Write-Host "TXT files: $(Get-AssetCount -Root $sampleFilesDirectory -SubDirectory "txt" -Extensions @(".txt"))"
    Write-Host "Unsupported files: $(Get-AssetCount -Root $sampleFilesDirectory -SubDirectory "unsupported" -Extensions @(".cbr", ".mobi", ".azw", ".azw3", ".md", ".html", ".booktest"))"
} else {
    Write-Host "Generate missing assets: .\tools\books\generate-books-test-assets.ps1"
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
Write-Host "Books environment check complete."
