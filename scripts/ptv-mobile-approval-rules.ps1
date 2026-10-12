Set-StrictMode -Version Latest

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
