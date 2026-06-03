[CmdletBinding()]
param(
    [string] $PropertiesPath = (Join-Path $PSScriptRoot "..\gradle.properties")
)

$propertyName = "piggietv.version"
$startVersion = "0.0.50"
$path = [System.IO.Path]::GetFullPath($PropertiesPath)
$pattern = "^\s*$([regex]::Escape($propertyName))\s*=\s*v?(?<major>\d{1,2})\.(?<minor>\d{1,2})\.(?<patch>\d{1,2})\s*$"

if (Test-Path -LiteralPath $path) {
    $lines = @(Get-Content -LiteralPath $path)
} else {
    $lines = @()
}

$versionLineIndex = -1
$currentVersion = $null

for ($index = 0; $index -lt $lines.Count; $index++) {
    $match = [regex]::Match($lines[$index], $pattern)
    if ($match.Success) {
        $versionLineIndex = $index
        $currentVersion = @{
            Major = [int] $match.Groups["major"].Value
            Minor = [int] $match.Groups["minor"].Value
            Patch = [int] $match.Groups["patch"].Value
        }
        break
    }
}

if ($versionLineIndex -eq -1) {
    $nextVersion = $startVersion
    if ($lines.Count -gt 0 -and $lines[-1].Trim() -ne "") {
        $lines += ""
    }
    $lines += "$propertyName=$nextVersion"
} else {
    $major = $currentVersion.Major
    $minor = $currentVersion.Minor
    $patch = $currentVersion.Patch

    if ($patch -lt 1) {
        $patch = 1
    } elseif ($patch -lt 99) {
        $patch += 1
    } else {
        $patch = 1
        if ($minor -lt 99) {
            $minor += 1
        } elseif ($major -lt 99) {
            $major += 1
            $minor = 0
        } else {
            throw "Version is already at the supported maximum 99.99.99."
        }
    }

    $nextVersion = "$major.$minor.$patch"
    $lines[$versionLineIndex] = "$propertyName=$nextVersion"
}

Set-Content -LiteralPath $path -Value $lines
Write-Output $nextVersion
