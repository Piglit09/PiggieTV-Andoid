Set-StrictMode -Version Latest

function Assert-PTVMobileLiteralRegistryValueKind {
    param([Parameter(Mandatory = $true)][Microsoft.Win32.RegistryValueKind]$Kind)
    if ($Kind -ne [Microsoft.Win32.RegistryValueKind]::String) {
        throw 'Protected approval policy must be a literal REG_SZ.'
    }
}

function Assert-PTVMobilePolicyFields {
    param($Record, [string[]]$Required)
    if ($null -eq $Record -or $Record -isnot [pscustomobject]) { throw 'Policy record is malformed.' }
    $actual = @($Record.PSObject.Properties | ForEach-Object { $_.Name })
    foreach ($field in $Required) {
        if ($actual -cnotcontains $field) { throw "Required policy field is absent: $field" }
    }
    foreach ($field in $actual) {
        if ($Required -cnotcontains $field) { throw "Unexpected policy field: $field" }
    }
}

function ConvertTo-PTVMobilePolicyPath {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path) -or -not [IO.Path]::IsPathRooted($Path)) {
        throw 'Policy path is not absolute.'
    }
    return [IO.Path]::GetFullPath($Path).TrimEnd([char[]]@('\', '/'))
}

function Resolve-PTVMobilePolicyFormat {
    param([Parameter(Mandatory = $true)]$Policy, [int]$MinimumSchemaVersion = 1)
    if ($MinimumSchemaVersion -notin @(1,2) -or $null -eq $Policy -or $Policy -isnot [pscustomobject]) {
        throw 'Protected policy format or minimum schema marker is invalid.'
    }
    $fields = @($Policy.PSObject.Properties | ForEach-Object { $_.Name })
    $hasVersion = $fields -ccontains 'schemaVersion'
    $hasProjects = $fields -ccontains 'projects'
    if ($hasVersion -and $hasProjects) {
        if (($Policy.schemaVersion -isnot [int] -and $Policy.schemaVersion -isnot [long]) -or
            $Policy.schemaVersion -ne 2) { throw 'Shared policy schema version is invalid.' }
        return 'SharedV2'
    }
    if ($hasVersion -or $hasProjects) { throw 'Partial shared policy cannot fall back to legacy authority.' }
    if ($MinimumSchemaVersion -ge 2) { throw 'Legacy policy is denied by the protected minimum schema marker.' }
    if ($fields -cnotcontains 'projectId' -or $Policy.projectId -cne 'PTV_WEBOS') {
        throw 'Only the protected legacy webOS policy may occupy the shared value.'
    }
    return 'LegacyWebOS'
}

function Resolve-PTVMobileSharedPolicy {
    param(
        [Parameter(Mandatory = $true)]$Policy,
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$CommonDirectory,
        [Parameter(Mandatory = $true)][string]$Remote,
        [Parameter(Mandatory = $true)][string]$ApplicationId,
        [Parameter(Mandatory = $true)][string]$VersionSource,
        [Parameter(Mandatory = $true)][string]$ArtifactRoot,
        [Parameter(Mandatory = $true)][string]$Mode
    )
    Assert-PTVMobilePolicyFields $Policy @('schemaVersion','policyId','issuedUtc','expiresUtc','projects','approvals')
    if (($Policy.schemaVersion -isnot [int] -and $Policy.schemaVersion -isnot [long]) -or $Policy.schemaVersion -ne 2 -or
        [string]::IsNullOrWhiteSpace([string]$Policy.policyId)) {
        throw 'Shared policy version or identity is invalid.'
    }
    foreach ($field in @('issuedUtc','expiresUtc')) {
        $value = [string]$Policy.$field
        if ($value -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$') {
            throw "Shared policy $field is not an ISO UTC timestamp."
        }
    }
    $issued = [DateTimeOffset]::Parse([string]$Policy.issuedUtc)
    $expiry = [DateTimeOffset]::Parse([string]$Policy.expiresUtc)
    if ($issued -gt [DateTimeOffset]::UtcNow -or $expiry -le [DateTimeOffset]::UtcNow -or $expiry -le $issued) {
        throw 'Shared policy issue or expiry time is invalid.'
    }
    if ($Policy.projects -isnot [array] -or @($Policy.projects).Count -ne 4 -or
        $Policy.approvals -isnot [array] -or @($Policy.approvals).Count -ne 0) {
        throw 'Shared policy projects or approvals are invalid.'
    }
    $known = @('PTV_WEB','PTV_ANDROID_TV','PTV_WEBOS','PTV_ANDROID_MOBILE')
    $seen = @{}
    $roots = @()
    $selected = $null
    foreach ($project in $Policy.projects) {
        if ($null -eq $project -or $project -isnot [pscustomobject]) { throw 'Shared project is malformed.' }
        $id = [string]$project.projectId
        if ($known -cnotcontains $id -or $seen.ContainsKey($id)) { throw 'Shared project identity is unknown or duplicated.' }
        $seen[$id] = $true
        if ($project.state -ceq 'HOLD') {
            Assert-PTVMobilePolicyFields $project @('projectId','state','reason')
            if ([string]::IsNullOrWhiteSpace([string]$project.reason)) { throw 'HOLD reason is absent.' }
        } elseif ($project.state -ceq 'ACTIVE') {
            Assert-PTVMobilePolicyFields $project @('projectId','state','sourceRoot','gitCommonDir','remote','applicationId','versionSource','artifactRoots','allowedModes','packageEnabled','externalOperations')
            if ($id -ceq 'PTV_WEB') { throw 'PTV Web authority remains on HOLD.' }
            $projectRoot = ConvertTo-PTVMobilePolicyPath ([string]$project.sourceRoot)
            $projectCommon = ConvertTo-PTVMobilePolicyPath ([string]$project.gitCommonDir)
            if (-not $projectCommon.Equals(($projectRoot + '\.git'), [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Shared project Git common directory is invalid.'
            }
            foreach ($prior in $roots) {
                if ($projectRoot.Equals($prior, [StringComparison]::OrdinalIgnoreCase) -or
                    $projectRoot.StartsWith($prior + '\', [StringComparison]::OrdinalIgnoreCase) -or
                    $prior.StartsWith($projectRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
                    throw 'Shared writable source roots overlap.'
                }
            }
            $roots += $projectRoot
            if ([string]::IsNullOrWhiteSpace([string]$project.remote) -or
                [string]::IsNullOrWhiteSpace([string]$project.applicationId) -or
                [string]::IsNullOrWhiteSpace([string]$project.versionSource) -or
                $project.artifactRoots -isnot [array] -or @($project.artifactRoots).Count -lt 1 -or
                $project.allowedModes -isnot [array] -or $project.externalOperations -isnot [array] -or
                $project.packageEnabled -isnot [bool] -or $project.packageEnabled -or
                @($project.externalOperations).Count -ne 0) {
                throw 'Shared project source or permissions are invalid.'
            }
            foreach ($artifactPath in $project.artifactRoots) { $null = ConvertTo-PTVMobilePolicyPath ([string]$artifactPath) }
            $modes = @($project.allowedModes)
            if (@($modes | Select-Object -Unique).Count -ne $modes.Count -or
                @($modes | Where-Object { $_ -cnotin @('Edit','Build') }).Count -ne 0) {
                throw 'Shared project modes are invalid or privileged.'
            }
        } else {
            throw 'Shared project state is invalid.'
        }
        if ($id -ceq 'PTV_ANDROID_MOBILE') { $selected = $project }
    }
    if ($null -eq $selected -or $selected.state -cne 'ACTIVE' -or $Mode -cnotin @('Edit','Build') -or
        @($selected.allowedModes) -cnotcontains $Mode) {
        throw 'Shared policy does not authorize the Mobile operation.'
    }
    if (-not (ConvertTo-PTVMobilePolicyPath $selected.sourceRoot).Equals((ConvertTo-PTVMobilePolicyPath $Root), [StringComparison]::OrdinalIgnoreCase) -or
        -not (ConvertTo-PTVMobilePolicyPath $selected.gitCommonDir).Equals((ConvertTo-PTVMobilePolicyPath $CommonDirectory), [StringComparison]::OrdinalIgnoreCase) -or
        $selected.remote -cne $Remote -or $selected.applicationId -cne $ApplicationId -or
        $selected.versionSource -cne $VersionSource -or @($selected.artifactRoots).Count -ne 1 -or
        -not (ConvertTo-PTVMobilePolicyPath $selected.artifactRoots[0]).Equals((ConvertTo-PTVMobilePolicyPath $ArtifactRoot), [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Shared Mobile source identity differs.'
    }
    return $selected
}

function Assert-PTVMobileDebugPackageTask {
    param([Parameter(Mandatory = $true)]$Policy, [Parameter(Mandatory = $true)][string[]]$GradleTasks)
    if ($GradleTasks.Count -ne 1) { throw 'Debug package approval requires one exact Gradle task.' }
    $allowed = @{
        ':app:assembleLibreDebug' = 'libreDebug'
        ':app:assembleProprietaryDebug' = 'proprietaryDebug'
    }
    $task = $GradleTasks[0]
    if (-not $allowed.ContainsKey($task) -or $Policy.approvedGradleTask -cne $task -or
        $Policy.approvedVariant -cne $allowed[$task] -or
        $Policy.applicationId -cne 'com.piggietv.android' -or
        $Policy.packageApplicationId -cne 'com.piggietv.android.debug') {
        throw 'Debug package task, flavor, variant, or application ID is not approved.'
    }
}

function Assert-PTVMobileDebugPackageEnvironment {
    param(
        [Parameter(Mandatory = $true)]$Policy,
        [Parameter(Mandatory = $true)][string]$JavaHome,
        [Parameter(Mandatory = $true)][string]$SdkRoot,
        [Parameter(Mandatory = $true)][string]$Root
    )
    $variant = [string]$Policy.approvedVariant
    $flavor = switch ($variant) {
        'libreDebug' { 'libre' }
        'proprietaryDebug' { 'proprietary' }
        default { throw 'Only a named debug variant may be packaged.' }
    }
    $expectedOutput = Join-Path $Root "app\build\outputs\apk\$flavor\debug"
    if (-not (ConvertTo-PTVMobilePolicyPath $Policy.approvedJavaHome).Equals((ConvertTo-PTVMobilePolicyPath $JavaHome), [StringComparison]::OrdinalIgnoreCase) -or
        -not (ConvertTo-PTVMobilePolicyPath $Policy.approvedSdkRoot).Equals((ConvertTo-PTVMobilePolicyPath $SdkRoot), [StringComparison]::OrdinalIgnoreCase) -or
        -not (ConvertTo-PTVMobilePolicyPath $Policy.approvedOutputDirectory).Equals((ConvertTo-PTVMobilePolicyPath $expectedOutput), [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Debug package toolchain or output directory differs from the approval.'
    }
}

function Assert-PTVMobileDebugPackageApproval {
    param(
        [Parameter(Mandatory = $true)]$Policy,
        [Parameter(Mandatory = $true)][string]$Head,
        [Parameter(Mandatory = $true)][string]$Tree,
        [Parameter(Mandatory = $true)][string]$Version,
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$CommonDirectory,
        [Parameter(Mandatory = $true)][string]$Remote,
        [Parameter(Mandatory = $true)][string]$ArtifactRoot,
        [Parameter(Mandatory = $true)][string[]]$GradleTasks,
        [Parameter(Mandatory = $true)][string]$JavaHome,
        [Parameter(Mandatory = $true)][string]$SdkRoot
    )
    Assert-PTVMobilePolicyFields $Policy @(
        'schemaVersion','projectId','sourceRoot','gitCommonDir','remote','applicationId',
        'sourceCommit','sourceTree','version','artifactRoot','approvalId','rollbackReference',
        'expiresUtc','allowedModes','productionApproval','releaseApproval','approvedGradleTask',
        'approvedVariant','packageApplicationId','approvedJavaHome','approvedSdkRoot','approvedOutputDirectory'
    )
    if (($Policy.schemaVersion -isnot [int] -and $Policy.schemaVersion -isnot [long]) -or
        $Policy.schemaVersion -ne 1 -or $Policy.projectId -cne 'PTV_ANDROID_MOBILE' -or
        $Policy.remote -cne $Remote -or $Policy.applicationId -cne 'com.piggietv.android' -or
        $Policy.sourceCommit -cne $Head -or $Policy.sourceTree -cne $Tree -or $Policy.version -cne $Version -or
        $Policy.sourceCommit -cnotmatch '^[a-f0-9]{40}$' -or $Policy.sourceTree -cnotmatch '^[a-f0-9]{40}$' -or
        -not (ConvertTo-PTVMobilePolicyPath $Policy.sourceRoot).Equals((ConvertTo-PTVMobilePolicyPath $Root), [StringComparison]::OrdinalIgnoreCase) -or
        -not (ConvertTo-PTVMobilePolicyPath $Policy.gitCommonDir).Equals((ConvertTo-PTVMobilePolicyPath $CommonDirectory), [StringComparison]::OrdinalIgnoreCase) -or
        -not (ConvertTo-PTVMobilePolicyPath $Policy.artifactRoot).Equals((ConvertTo-PTVMobilePolicyPath $ArtifactRoot), [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Debug package source identity differs from protected approval.'
    }
    if ([string]::IsNullOrWhiteSpace([string]$Policy.approvalId) -or
        [string]::IsNullOrWhiteSpace([string]$Policy.rollbackReference) -or
        $Policy.expiresUtc -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$' -or
        [DateTimeOffset]::Parse([string]$Policy.expiresUtc) -le [DateTimeOffset]::UtcNow -or
        $Policy.allowedModes -isnot [array] -or @($Policy.allowedModes).Count -ne 1 -or
        @($Policy.allowedModes)[0] -cne 'Package' -or
        $Policy.productionApproval -isnot [bool] -or $Policy.productionApproval -ne $false -or
        $Policy.releaseApproval -isnot [bool] -or $Policy.releaseApproval -ne $false) {
        throw 'Debug package approval is absent, expired, or broader than Package.'
    }
    Assert-PTVMobileDebugPackageTask -Policy $Policy -GradleTasks $GradleTasks
    Assert-PTVMobileDebugPackageEnvironment -Policy $Policy -JavaHome $JavaHome -SdkRoot $SdkRoot -Root $Root
}

function Assert-PTVMobileRegistryAcl {
    param(
        [Parameter(Mandatory = $true)][Security.AccessControl.RegistrySecurity]$Acl,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    $owner = $Acl.GetOwner([Security.Principal.SecurityIdentifier])
    if ($owner -eq $identity.User -or $principal.IsInRole($owner)) {
        throw "Approval Registry path can be repermissioned by this process: $Path"
    }

    $writeRights = [Security.AccessControl.RegistryRights]::SetValue -bor
        [Security.AccessControl.RegistryRights]::CreateSubKey -bor
        [Security.AccessControl.RegistryRights]::CreateLink -bor
        [Security.AccessControl.RegistryRights]::Delete -bor
        [Security.AccessControl.RegistryRights]::ChangePermissions -bor
        [Security.AccessControl.RegistryRights]::TakeOwnership
    $rules = $Acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])
    foreach ($rule in $rules) {
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) {
            continue
        }
        $sid = [Security.Principal.SecurityIdentifier]$rule.IdentityReference
        if (($rule.RegistryRights -band $writeRights) -ne 0 -and
            ($sid -eq $identity.User -or $principal.IsInRole($sid))) {
            throw "Approval Registry path is writable by this process: $Path"
        }
    }
}

function Assert-PTVMobileTargetApproval {
    param(
        [Parameter(Mandatory = $true)][string]$Mode,
        [Parameter(Mandatory = $true)]$Policy
    )

    $targetClass = [string]$Policy.targetClass
    if ($Mode -eq 'Install') {
        if ($targetClass -notin @('TEST_DEVICE', 'PRODUCTION_DEVICE')) {
            throw 'Install target class is absent or unsupported.'
        }
        if ($targetClass -eq 'PRODUCTION_DEVICE' -and
            ($Policy.productionApproval -ne $true -or
                [string]::IsNullOrWhiteSpace([string]$Policy.productionApprovalId))) {
            throw 'Production device requires a separate operator approval.'
        }
    } elseif ($Mode -eq 'Publish') {
        if ($targetClass -ne 'RELEASE_CHANNEL' -or
            $Policy.releaseApproval -ne $true -or
            [string]::IsNullOrWhiteSpace([string]$Policy.releaseApprovalId)) {
            throw 'Publication requires a separate approved release channel record.'
        }
    } else {
        throw 'Target approval can only authorize Install or Publish.'
    }
}
