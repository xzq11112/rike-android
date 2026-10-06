# Synthetic Windows PowerShell 5.1 acceptance; no real key or password is used.
param([Parameter(Mandatory=$true)][string]$Apk, [Parameter(Mandatory=$true)][string]$Signer)
$ErrorActionPreference='Stop'
if ($PSVersionTable.PSVersion.Major -ne 5 -or $PSVersionTable.PSVersion.Minor -ne 1) { throw 'Run with Windows PowerShell 5.1.' }
$rikeTempRoot=$env:RUNNER_TEMP
if (-not $rikeTempRoot) { $rikeTempRoot=$env:TEMP }
$rikeTestRoot=Join-Path $rikeTempRoot ('rike-test-' + [guid]::NewGuid().ToString('N'))
$rikeSavedLocalAppData=$env:LOCALAPPDATA
$env:RIKE_SYNTHETIC_KS='Synthetic-CI-Only-Password-71629'
New-Item -ItemType Directory -Path $rikeTestRoot | Out-Null
$rikeJava=Join-Path $env:JAVA_HOME 'bin\java.exe'
$rikeKeytool=Join-Path $env:JAVA_HOME 'bin\keytool.exe'
$rikeOriginalKey=Join-Path $rikeTestRoot '原密钥 with spaces.p12'
$rikeChecks=New-Object 'System.Collections.Generic.List[string]'
function Assert-RikeTest([bool]$Check,[string]$Message){if(-not $Check){throw $Message};$rikeChecks.Add($Message);Write-Host ('PASS: '+$Message)}
# Only these disposable fixtures receive ACL changes; restore the original ACL
# in finally before any retry or deletion, even when an assertion fails.
function Deny-RikeFixtureWrite([string]$Path) {
    $rikeOriginalAcl = Get-Acl -LiteralPath $Path
    $rikeRestrictedAcl = Get-Acl -LiteralPath $Path
    $rikeSid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $rikeInheritance = [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit
    $rikeRule = [Security.AccessControl.FileSystemAccessRule]::new($rikeSid, [Security.AccessControl.FileSystemRights]::Write,
        $rikeInheritance, [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Deny)
    $rikeRestrictedAcl.AddAccessRule($rikeRule)
    Set-Acl -LiteralPath $Path -AclObject $rikeRestrictedAcl
    return $rikeOriginalAcl
}
function Run-RikeScenario([string]$Name,[string[]]$Answers,[string]$KeyRoot,[bool]$BadPassword=$false,[bool]$FailSign=$false,[bool]$BlockOutput=$false,[bool]$RepairAlias=$false){
    $rikeCase=Join-Path $rikeTestRoot ($Name+' 中文路径 with spaces')
    New-Item -ItemType Directory -Path $rikeCase -Force | Out-Null
    if($BlockOutput){[IO.File]::WriteAllText((Join-Path $rikeCase 'output'),'synthetic output blocker')}
    Copy-Item $Apk (Join-Path $rikeCase 'rike-0.6.4-release-unsigned.apk')
    Copy-Item $Signer (Join-Path $rikeCase 'apksigner.jar')
    Copy-Item (Join-Path $PSScriptRoot 'RikeLocalTool.java') (Join-Path $rikeCase 'RikeLocalTool.java')
    $rikeScript=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'sign-release.ps1'))
    $rikeActualInputHash=(Get-FileHash $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
    $rikeHashPattern='(?m)^\$rikeInputHash = ''[^'']+''\r?$'
    if ([regex]::Matches($rikeScript,$rikeHashPattern).Count -ne 1) {
        throw 'Synthetic signing test requires exactly one APK hash assignment.'
    }
    $rikeScript=$rikeScript -replace $rikeHashPattern, ("`$rikeInputHash = '"+$rikeActualInputHash+"'")
    $rikeScript=$rikeScript -replace 'Source build: [0-9a-zA-Z_]+', ('Source build: '+$env:GITHUB_SHA)

    $rikeScriptPath=Join-Path $rikeCase 'sign-release.ps1'
    [IO.File]::WriteAllText($rikeScriptPath,$rikeScript,[Text.Encoding]::UTF8)
    $env:LOCALAPPDATA=$KeyRoot
    . $rikeScriptPath -CorrectAlias:$RepairAlias
    $rikeAnswers=New-Object 'System.Collections.Generic.Queue[string]'
    foreach($answer in $Answers){$rikeAnswers.Enqueue($answer)}
    function Read-Host([string]$Prompt){if($rikeAnswers.Count -eq 0){throw ('Unexpected prompt: '+$Prompt)};return $rikeAnswers.Dequeue()}
    function Find-RikeJava{return @{Java=$rikeJava;Keytool=$rikeKeytool}}
    function Invoke-RikeGenerate([string]$Tool,[string]$Store,[string]$Alias){Invoke-RikeTool $rikeJava 'keytool' $Tool @('-genkeypair','-keystore',$Store,'-storetype','PKCS12','-alias',$Alias,'-keyalg','RSA','-keysize','2048','-validity','30','-dname','CN=Synthetic Windows New Key','-storepass:env','RIKE_SYNTHETIC_KS') | Out-Host}
    function Invoke-RikeSign([string]$Java,[string]$Tool,[string]$Store,[string]$Alias,[string]$Output,[string]$InputApk){
        if($FailSign){$global:LASTEXITCODE=1;return}
        if($BadPassword){$env:RIKE_SYNTHETIC_WRONG='deliberately-wrong';$rikePasswordEnv='RIKE_SYNTHETIC_WRONG'}else{$rikePasswordEnv='RIKE_SYNTHETIC_KS'}
        Invoke-RikeTool $Java 'apksigner' $Tool @('sign','--ks',$Store,'--ks-key-alias',$Alias,'--ks-pass',('env:'+$rikePasswordEnv),'--debuggable-apk-permitted','false','--alignment-preserved','true','--v1-signing-enabled','false','--v2-signing-enabled','true','--v3-signing-enabled','true','--v4-signing-enabled','false','--out',$Output,$InputApk) | Out-Host
    }
    $rikeOk=Invoke-RikeRelease
    return @{Ok=$rikeOk;Case=$rikeCase;Root=(Join-Path $KeyRoot 'RikeRelease')}
}
try{
    # Fixture generation also crosses the same Unicode-safe Java launcher bridge.
    . (Join-Path $PSScriptRoot 'sign-release.ps1')
    Assert-RikeHash $rikeBridge $rikeBridgeHash
    Assert-RikeHash $Signer $rikeToolHash
    Invoke-RikeTool $rikeJava 'keytool' $rikeKeytool @('-genkeypair','-keystore',$rikeOriginalKey,'-storetype','PKCS12','-alias','original','-keyalg','RSA','-keysize','2048','-validity','30','-dname','CN=Synthetic Windows Test','-storepass:env','RIKE_SYNTHETIC_KS') | Out-Host
    $rikeMutex.Dispose()
    if ($LASTEXITCODE -ne 0) { throw 'Fixture generation failed.' }
    $rikeOriginalHash=(Get-FileHash $rikeOriginalKey -Algorithm SHA256).Hash
    $preflightRoot=Join-Path $rikeTestRoot 'known-valid-preflight-state'
    $r=Run-RikeScenario 'known-valid-preflight' @('2',$rikeOriginalKey,'original') $preflightRoot
    if (-not $r.Ok) { throw 'Environment preflight: a known valid fixture must sign before failure cases run.' }
    $root=Join-Path $rikeTestRoot 'import-state'
    $r=Run-RikeScenario 'wrong-alias' @('2',$rikeOriginalKey,'wrong-alias') $root
    Assert-RikeTest (-not $r.Ok -and -not(Test-Path (Join-Path $r.Root 'rike-release.p12'))) 'Wrong alias does not become default'
    $r=Run-RikeScenario 'wrong-password' @('2',$rikeOriginalKey,'original') $root $true
    Assert-RikeTest (-not $r.Ok -and -not(Test-Path (Join-Path $r.Root 'key-alias.txt'))) 'Wrong password keeps configuration uninstalled'
    $bad=Join-Path $rikeTestRoot 'wrong-file.p12';[IO.File]::WriteAllText($bad,'synthetic invalid bytes')
    $r=Run-RikeScenario 'wrong-file' @('2',$bad,'original') $root
    Assert-RikeTest (-not $r.Ok -and -not(Test-Path (Join-Path $r.Root 'rike-release.p12'))) 'Wrong file can be reselected on next run'
    $r=Run-RikeScenario 'valid-import' @('2',$rikeOriginalKey,'original') $root
    Assert-RikeTest ($r.Ok -and (Test-Path (Join-Path $r.Case 'output\rike-0.6.4-release.apk.txt'))) 'Correct import publishes APK with verification receipt'
    Assert-RikeTest ((Get-FileHash $rikeOriginalKey -Algorithm SHA256).Hash -eq $rikeOriginalHash) 'Source key bytes unchanged'
    $identity=[IO.File]::ReadAllText((Join-Path $r.Root 'signer-sha256.txt'))
    [IO.File]::WriteAllText((Join-Path $r.Root 'key-alias.txt'),'wrong-alias')
    $r=Run-RikeScenario 'correct-alias' @('original') $root $false $false $false $true
    Assert-RikeTest ($r.Ok -and [IO.File]::ReadAllText((Join-Path $r.Root 'key-alias.txt')) -eq 'original') 'Existing alias correction is verified before persistence'
    Remove-Item (Join-Path $r.Root 'key-alias.txt')
    $r=Run-RikeScenario 'missing-alias' @('original') $root
    Assert-RikeTest ($r.Ok -and [IO.File]::ReadAllText((Join-Path $r.Root 'signer-sha256.txt')) -eq $identity) 'Missing alias repaired with same identity'
    $newRoot=Join-Path $rikeTestRoot 'new-state'
    $r=Run-RikeScenario 'new-key-sign-failure' @('1') $newRoot $false $true
    Assert-RikeTest (-not $r.Ok -and (Test-Path (Join-Path $r.Root 'rike-release.p12'))) 'Valid newly generated key survives signing failure'
    $r=Run-RikeScenario 'new-key-retry' @() $newRoot
    Assert-RikeTest $r.Ok 'Retry uses retained generated key'
    # Repeated and interrupted publication must preserve existing outputs.
    $r=Run-RikeScenario 'same-identity-again' @() $root
    Assert-RikeTest $r.Ok 'Existing key signs automatically without another Read-Host prompt'
    Assert-RikeTest ($r.Ok -and [IO.File]::ReadAllText((Join-Path $r.Root 'signer-sha256.txt')) -eq $identity) 'Repeated signing keeps certificate identity'
    $r=Run-RikeScenario 'same-identity-again' @() $root
    Assert-RikeTest ($r.Ok -and @(Get-ChildItem (Join-Path $r.Case 'output') -Filter '*.apk').Count -eq 2) 'Same-name outputs are preserved with a new filename'
    $orphanRoot=Join-Path $rikeTestRoot 'orphan-receipt-state'
    $r=Run-RikeScenario 'orphan-receipt' @('2',$rikeOriginalKey,'original') $orphanRoot
    Remove-Item (Join-Path $r.Case 'output\rike-0.6.4-release.apk')
    $r=Run-RikeScenario 'orphan-receipt' @() $orphanRoot
    Assert-RikeTest ($r.Ok -and @(Get-ChildItem (Join-Path $r.Case 'output') -Filter '*.apk').Count -eq 1) 'A receipt left by interrupted publication does not block retry'
    $blockedRoot=Join-Path $rikeTestRoot 'blocked-output-state'
    $r=Run-RikeScenario 'blocked-output' @('1') $blockedRoot $false $false $true
    Assert-RikeTest (-not $r.Ok -and (Test-Path (Join-Path $r.Root 'rike-release.p12'))) 'Blocked output path retains valid generated key'
    Remove-Item (Join-Path $r.Case 'output')
    $r=Run-RikeScenario 'blocked-output' @() $blockedRoot
    Assert-RikeTest $r.Ok 'Output-path retry signs with the retained generated key'
    $blockedKeyRoot=Join-Path $rikeTestRoot 'blocked-key-state'
    New-Item -ItemType Directory -Path $blockedKeyRoot | Out-Null
    [IO.File]::WriteAllText((Join-Path $blockedKeyRoot 'RikeRelease'),'synthetic key directory blocker')
    $r=Run-RikeScenario 'blocked-key-directory' @('1') $blockedKeyRoot
    Assert-RikeTest (-not $r.Ok -and -not(Test-Path (Join-Path $r.Case 'output\rike-0.6.4-release.apk'))) 'Blocked key directory publishes no APK'
    Remove-Item (Join-Path $blockedKeyRoot 'RikeRelease')
    $r=Run-RikeScenario 'blocked-key-directory' @('2',$rikeOriginalKey,'original') $blockedKeyRoot
    Assert-RikeTest $r.Ok 'Key-directory retry imports the original key successfully'
    $aclKeyRoot=Join-Path $rikeTestRoot 'acl-key-state'
    $aclKeyDirectory=Join-Path $aclKeyRoot 'RikeRelease'
    New-Item -ItemType Directory -Path $aclKeyDirectory -Force | Out-Null
    $rikeSavedAcl=Deny-RikeFixtureWrite $aclKeyDirectory
    try {
        $r=Run-RikeScenario 'acl-key-denied' @('1') $aclKeyRoot
        Assert-RikeTest (-not $r.Ok -and -not(Test-Path (Join-Path $r.Root 'rike-release.p12'))) 'Real key-directory write denial leaves no default key'
    } finally { Set-Acl -LiteralPath $aclKeyDirectory -AclObject $rikeSavedAcl }
    $r=Run-RikeScenario 'acl-key-denied' @('2',$rikeOriginalKey,'original') $aclKeyRoot
    Assert-RikeTest $r.Ok 'Restored key-directory permission allows original-key import'
    $aclOutputRoot=Join-Path $rikeTestRoot 'acl-output-key-state'
    $aclOutputCase=Join-Path $rikeTestRoot 'acl-output-denied 中文路径 with spaces'
    $aclOutputDirectory=Join-Path $aclOutputCase 'output'
    New-Item -ItemType Directory -Path $aclOutputDirectory -Force | Out-Null
    $rikeSavedAcl=Deny-RikeFixtureWrite $aclOutputDirectory
    try {
        $r=Run-RikeScenario 'acl-output-denied' @('1') $aclOutputRoot
        Assert-RikeTest (-not $r.Ok -and (Test-Path (Join-Path $r.Root 'rike-release.p12')) -and -not(Test-Path (Join-Path $aclOutputDirectory 'rike-0.6.4-release.apk'))) 'Real output-directory write denial retains a valid new key without an APK'
        $rikeRetainedKeyHash=(Get-FileHash (Join-Path $r.Root 'rike-release.p12') -Algorithm SHA256).Hash
    } finally { Set-Acl -LiteralPath $aclOutputDirectory -AclObject $rikeSavedAcl }
    $r=Run-RikeScenario 'acl-output-denied' @() $aclOutputRoot
    Assert-RikeTest ($r.Ok -and (Get-FileHash (Join-Path $r.Root 'rike-release.p12') -Algorithm SHA256).Hash -eq $rikeRetainedKeyHash) 'Restored output permission retries with unchanged key bytes'
    # Only the original certificate identity may restore a missing keystore.
    Remove-Item (Join-Path $root 'RikeRelease\rike-release.p12')
    $r=Run-RikeScenario 'missing-key-original' @($rikeOriginalKey,'original') $root
    Assert-RikeTest ($r.Ok -and [IO.File]::ReadAllText((Join-Path $r.Root 'signer-sha256.txt')) -eq $identity) 'Missing keystore can be restored by selecting the original identity'
    Write-Host ('Windows PowerShell 5.1: '+$rikeChecks.Count+' checks passed')
}finally{
    $env:LOCALAPPDATA=$rikeSavedLocalAppData
    Remove-Item Env:RIKE_SYNTHETIC_KS -ErrorAction SilentlyContinue
    Remove-Item Env:RIKE_SYNTHETIC_WRONG -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $rikeTestRoot -Recurse -Force -ErrorAction SilentlyContinue
}
