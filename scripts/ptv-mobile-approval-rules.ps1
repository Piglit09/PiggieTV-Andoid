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

function Assert-PTVMobileSharedWebV3Entry {
    param([Parameter(Mandatory = $true)]$Project)
    Assert-PTVMobilePolicyFields $Project @(
        'projectId','state','sourceRoot','gitCommonDir','remote','artifactRoots',
        'permissions','developmentDeployment'
    )
    foreach ($field in @('projectId','state','sourceRoot','gitCommonDir','remote')) {
        if ($Project.$field -isnot [string]) { throw "Shared Web v3 $field must be a scalar string." }
    }
    if ($Project.artifactRoots -isnot [array] -or @($Project.artifactRoots).Count -ne 1 -or
        $Project.artifactRoots[0] -isnot [string]) {
        throw 'Shared Web v3 artifact root must be one scalar string.'
    }
    if ($Project.projectId -cne 'PTV_WEB' -or $Project.state -cne 'ACTIVE' -or
        -not (ConvertTo-PTVMobilePolicyPath ([string]$Project.sourceRoot)).Equals(
            'C:\Users\Piggie\Documents\GitHub\Jellyfin-Web-PiggieTV', [StringComparison]::OrdinalIgnoreCase) -or
        -not (ConvertTo-PTVMobilePolicyPath ([string]$Project.gitCommonDir)).Equals(
            'C:\Users\Piggie\Documents\GitHub\Jellyfin-Web-PiggieTV\.git', [StringComparison]::OrdinalIgnoreCase) -or
        $Project.remote -cne 'https://github.com/Piglit09/PiggieTV-Jellyfin-web-app.git' -or
        $Project.artifactRoots -isnot [array] -or @($Project.artifactRoots).Count -ne 1 -or
        -not (ConvertTo-PTVMobilePolicyPath ([string]$Project.artifactRoots[0])).Equals(
            'C:\Users\Piggie\Documents\GitHub\_PTV-Artifacts', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Shared Web v3 source identity differs from the protected development entry.'
    }
    Assert-PTVMobilePolicyFields $Project.permissions @(
        'sourceEdit','localBuild','debugPackage','testDeviceInstall','testDeploy125',
        'githubPublish','productionDeploy50'
    )
    foreach ($permission in @('sourceEdit','localBuild','debugPackage','testDeviceInstall','testDeploy125','githubPublish','productionDeploy50')) {
        if ($Project.permissions.$permission -isnot [bool]) { throw 'Shared Web permission must be a boolean.' }
    }
    if (-not $Project.permissions.sourceEdit -or -not $Project.permissions.localBuild -or
        -not $Project.permissions.testDeploy125 -or $Project.permissions.debugPackage -or
        $Project.permissions.testDeviceInstall -or $Project.permissions.githubPublish -or
        $Project.permissions.productionDeploy50) {
        throw 'Shared Web v3 permissions differ from the protected development entry.'
    }
    Assert-PTVMobilePolicyFields $Project.developmentDeployment @(
        'environmentId','host','serverId','serverName','webTargetPath','pluginTargetRoot'
    )
    $deployment = $Project.developmentDeployment
    foreach ($field in @('environmentId','host','serverId','serverName','webTargetPath','pluginTargetRoot')) {
        if ($deployment.$field -isnot [string]) { throw "Shared Web v3 deployment $field must be a scalar string." }
    }
    if ($deployment.environmentId -cne 'JF_DEVELOPMENT_125' -or
        $deployment.host -cne '10.16.0.125' -or
        $deployment.serverId -cne 'c1858e3f2d09468fb546ad9cfe438283' -or
        $deployment.serverName -cne 'PTV Testing' -or
        -not (ConvertTo-PTVMobilePolicyPath ([string]$deployment.webTargetPath)).Equals(
            'C:\Program Files\Jellyfin\Server\jellyfin-web', [StringComparison]::OrdinalIgnoreCase) -or
        -not (ConvertTo-PTVMobilePolicyPath ([string]$deployment.pluginTargetRoot)).Equals(
            'C:\JFData\plugins\PiggieTV_Plugin (testing)', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Shared Web v3 deployment target differs from the protected development entry.'
    }
}

function Resolve-PTVMobilePolicyFormat {
    param([Parameter(Mandatory = $true)]$Policy, [int]$MinimumSchemaVersion = 1)
    if ($MinimumSchemaVersion -notin @(1,2,3) -or $null -eq $Policy -or $Policy -isnot [pscustomobject]) {
        throw 'Protected policy format or minimum schema marker is invalid.'
    }
    $fields = @($Policy.PSObject.Properties | ForEach-Object { $_.Name })
    $hasVersion = $fields -ccontains 'schemaVersion'
    $hasProjects = $fields -ccontains 'projects'
    if ($hasVersion -and $hasProjects) {
        if (($Policy.schemaVersion -isnot [int] -and $Policy.schemaVersion -isnot [long]) -or
            $Policy.schemaVersion -notin @(2,3) -or $Policy.schemaVersion -lt $MinimumSchemaVersion) {
            throw 'Shared policy schema version is invalid.'
        }
        return "SharedV$($Policy.schemaVersion)"
    }
    if ($hasVersion -or $hasProjects) { throw 'Partial shared policy cannot fall back to legacy authority.' }
    if ($MinimumSchemaVersion -ge 2) { throw 'Legacy policy is denied by the protected minimum schema marker.' }
    if ($fields -cnotcontains 'projectId' -or $Policy.projectId -isnot [string] -or
        $Policy.projectId -cne 'PTV_WEBOS') {
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
    foreach ($field in @('policyId','issuedUtc','expiresUtc')) {
        if ($Policy.$field -isnot [string]) { throw "Shared policy $field must be a scalar string." }
    }
    if (($Policy.schemaVersion -isnot [int] -and $Policy.schemaVersion -isnot [long]) -or $Policy.schemaVersion -notin @(2,3) -or
        [string]::IsNullOrWhiteSpace($Policy.policyId)) {
        throw 'Shared policy version or identity is invalid.'
    }
    foreach ($field in @('issuedUtc','expiresUtc')) {
        $value = $Policy.$field
        if ($value -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$') {
            throw "Shared policy $field is not an ISO UTC timestamp."
        }
    }
    $issued = [DateTimeOffset]::Parse($Policy.issuedUtc)
    $expiry = [DateTimeOffset]::Parse($Policy.expiresUtc)
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
        foreach ($field in @('projectId','state')) {
            if ($project.$field -isnot [string]) { throw "Shared project $field must be a scalar string." }
        }
        $id = $project.projectId
        if ($known -cnotcontains $id -or $seen.ContainsKey($id)) { throw 'Shared project identity is unknown or duplicated.' }
        $seen[$id] = $true
        if ($project.state -ceq 'HOLD') {
            Assert-PTVMobilePolicyFields $project @('projectId','state','reason')
            if ($project.reason -isnot [string] -or [string]::IsNullOrWhiteSpace($project.reason)) {
                throw 'HOLD reason must be a nonempty scalar string.'
            }
        } elseif ($project.state -ceq 'ACTIVE') {
            foreach ($field in @('sourceRoot','gitCommonDir','remote')) {
                if ($project.$field -isnot [string]) { throw "Shared project $field must be a scalar string." }
            }
            if ($Policy.schemaVersion -eq 2) {
                Assert-PTVMobilePolicyFields $project @('projectId','state','sourceRoot','gitCommonDir','remote','applicationId','versionSource','artifactRoots','allowedModes','packageEnabled','externalOperations')
            } else {
                if ($id -ceq 'PTV_WEB') {
                    Assert-PTVMobileSharedWebV3Entry $project
                } else {
                    Assert-PTVMobilePolicyFields $project @('projectId','state','sourceRoot','gitCommonDir','remote','applicationId','versionSource','artifactRoots','permissions')
                    Assert-PTVMobilePolicyFields $project.permissions @('sourceEdit','localBuild','debugPackage','testDeviceInstall','testDeploy125','githubPublish','productionDeploy50')
                    foreach ($permission in @('sourceEdit','localBuild','debugPackage','testDeviceInstall','testDeploy125','githubPublish','productionDeploy50')) {
                        if ($project.permissions.$permission -isnot [bool]) { throw 'Shared project permission must be a boolean.' }
                    }
                    if ($id -eq 'PTV_WEBOS' -and $project.permissions.debugPackage) {
                        throw 'webOS cannot authorize Android debug packaging.'
                    }
                    if ($project.permissions.testDeviceInstall -or $project.permissions.testDeploy125 -or
                        $project.permissions.githubPublish -or $project.permissions.productionDeploy50) {
                        throw 'Shared v3 policy cannot grant unimplemented external operations.'
                    }
                }
            }
            if ($Policy.schemaVersion -eq 2 -or $id -cne 'PTV_WEB') {
                foreach ($field in @('applicationId','versionSource')) {
                    if ($project.$field -isnot [string]) { throw "Shared project $field must be a scalar string." }
                }
            }
            if ($id -ceq 'PTV_WEB' -and $Policy.schemaVersion -eq 2) { throw 'PTV Web is not authorized in shared v2.' }
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
                $project.artifactRoots -isnot [array] -or @($project.artifactRoots).Count -lt 1 -or
                ($id -cne 'PTV_WEB' -and
                    ([string]::IsNullOrWhiteSpace([string]$project.applicationId) -or
                     [string]::IsNullOrWhiteSpace([string]$project.versionSource)))) {
                throw 'Shared project source or permissions are invalid.'
            }
            foreach ($artifactPath in $project.artifactRoots) {
                if ($artifactPath -isnot [string]) { throw 'Shared artifact root must be a scalar string.' }
                $null = ConvertTo-PTVMobilePolicyPath $artifactPath
            }
            if ($Policy.schemaVersion -eq 2) {
                if ($project.allowedModes -isnot [array] -or $project.externalOperations -isnot [array] -or
                    $project.packageEnabled -isnot [bool] -or $project.packageEnabled -or
                    @($project.externalOperations).Count -ne 0) {
                    throw 'Shared project source or permissions are invalid.'
                }
                $modes = @($project.allowedModes)
                if (@($modes | Select-Object -Unique).Count -ne $modes.Count -or
                    @($modes | Where-Object { $_ -cnotin @('Edit','Build') }).Count -ne 0) {
                    throw 'Shared project modes are invalid or privileged.'
                }
            }
        } else {
            throw 'Shared project state is invalid.'
        }
        if ($id -ceq 'PTV_ANDROID_MOBILE') { $selected = $project }
    }
    if ($null -eq $selected -or $selected.state -cne 'ACTIVE') {
        throw 'Shared policy does not authorize the Mobile operation.'
    }
    if ($Policy.schemaVersion -eq 2) {
        if ($Mode -cnotin @('Edit','Build') -or @($selected.allowedModes) -cnotcontains $Mode) {
            throw 'Shared policy does not authorize the Mobile operation.'
        }
    } else {
        $permission = switch -CaseSensitive ($Mode) {
            'Edit' { 'sourceEdit' }
            'Build' { 'localBuild' }
            'DebugPackage' { 'debugPackage' }
            default { throw 'Shared policy does not authorize the Mobile operation.' }
        }
        if (-not $selected.permissions.$permission) { throw 'Shared policy does not authorize the Mobile operation.' }
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

function Assert-PTVMobileDevelopmentDebugTasks {
    param([Parameter(Mandatory = $true)][string[]]$GradleTasks)
    if ($GradleTasks.Count -ne 1 -or
        $GradleTasks[0] -cnotin @(':app:assembleLibreDebug', ':app:assembleProprietaryDebug')) {
        throw 'Development packaging requires one exact approved debug APK task.'
    }
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
