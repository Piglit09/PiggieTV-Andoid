[CmdletBinding()]
param(
    [string] $PropertiesPath
)

if ([string]::IsNullOrWhiteSpace($PropertiesPath)) {
    $PropertiesPath = Join-Path $PSScriptRoot "..\gradle.properties"
}

$approvedProperties = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\gradle.properties'))
$requestedProperties = [System.IO.Path]::GetFullPath($PropertiesPath)
if (-not $requestedProperties.Equals($approvedProperties, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Version edits are limited to the approved Android Mobile gradle.properties.'
}
$preflight = Join-Path $PSScriptRoot 'ptv-mobile-workspace-preflight.ps1'
& powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $preflight -Mode Edit
if ($LASTEXITCODE -ne 0) {
    throw 'PTV Mobile source-edit preflight denied the version change.'
}

$propertyName = "piggietv.version"
$path = [System.IO.Path]::GetFullPath($PropertiesPath)
$pattern = "^\s*$([regex]::Escape($propertyName))\s*=\s*v?(?<major>\d{1,2})\.(?<minor>\d{1,2})\.(?<patch>\d{1,2})\s*$"

if (-not (Test-Path -LiteralPath $path)) {
    throw "Version properties file was not found: $path"
}

$lines = @(Get-Content -LiteralPath $path)

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
    throw "Required version property '$propertyName' was not found in $path"
} else {
    $major = $currentVersion.Major
    $minor = $currentVersion.Minor
    $patch = $currentVersion.Patch

    if ($patch -lt 99) {
        $patch += 1
    } elseif ($minor -lt 99) {
        $minor += 1
        $patch = 0
    } else {
        throw "Automatic major-version rollover is disabled at $major.99.99. Set the next major release explicitly."
    }

    $nextVersion = "$major.$minor.$patch"
    $lines[$versionLineIndex] = "$propertyName=$nextVersion"
}

[System.IO.File]::WriteAllLines($path, $lines, [System.Text.UTF8Encoding]::new($false))
Write-Output $nextVersion
