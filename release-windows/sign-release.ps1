# Local release signing only. Passwords are entered into the official Java tools.
# This script never receives, stores, uploads or logs a signing password.
param([switch]$CorrectAlias)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

$rikePackage = 'app.rike.offline'
$rikeVersion = '0.6.4'
$rikeInputHash = '4802137325bebf5144f65b5097186de665096fcb54520bce6468ad5e17e47f29'
$rikeToolHash = '00ef9948f843fe395d2440ae3ef41405b8040a6d5d46493bd1902ac0ee6deae7'
$rikeInput = Join-Path $PSScriptRoot 'rike-0.6.4-release-unsigned.apk'
$rikeTool = Join-Path $PSScriptRoot 'apksigner.jar'
$rikeBridge = Join-Path $PSScriptRoot 'RikeLocalTool.java'
$rikeBridgeHash = '3782fbd4dbb3e2421ad48174e9a489705bbc6caed70bc59d380f490009ebba01'
$rikeKeyRoot = Join-Path $env:LOCALAPPDATA 'RikeRelease'
$rikeKeystore = Join-Path $rikeKeyRoot 'rike-release.p12'
$rikeAliasFile = Join-Path $rikeKeyRoot 'key-alias.txt'
$rikeIdentityFile = Join-Path $rikeKeyRoot 'signer-sha256.txt'
$rikeDownloadUrl = 'https://adoptium.net/temurin/releases/?version=17&os=windows&arch=x64&package=jdk'
$rikePendingApk = $null
$rikePendingKey = $null
$rikePendingReceipt = $null
$rikeCandidateImport = $false
$rikeAliasChanged = $false
$rikeSigningKey = $rikeKeystore
$rikeOwnsMutex = $false
$rikeMutex = [System.Threading.Mutex]::new($false, 'Local\RikeReleaseSigning')

function Assert-RikeHash([string]$Path, [string]$Expected) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw ('文件不完整，请先解压整个签名包：' + [IO.Path]::GetFileName($Path))
    }
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Expected) {
        throw ('文件校验失败，请重新下载：' + [IO.Path]::GetFileName($Path))
    }
}

function Find-RikeJava {
    $rikeCandidates = New-Object 'System.Collections.Generic.List[string]'
    if ($env:JAVA_HOME) { $rikeCandidates.Add((Join-Path $env:JAVA_HOME 'bin')) }
    foreach ($rikeBase in @($env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:LOCALAPPDATA)) {
        if (-not $rikeBase) { continue }
        $rikeCandidates.Add((Join-Path $rikeBase 'Android\Android Studio\jbr\bin'))
        foreach ($rikePattern in @('Eclipse Adoptium\jdk-*\bin', 'Java\jdk*\bin', 'Microsoft\jdk-*\bin', 'Programs\Eclipse Adoptium\jdk-*\bin')) {
            foreach ($rikeDir in @(Get-Item -Path (Join-Path $rikeBase $rikePattern) -ErrorAction SilentlyContinue)) {
                $rikeCandidates.Add($rikeDir.FullName)
            }
        }
    }
    $rikeCommand = Get-Command 'java.exe' -ErrorAction SilentlyContinue
    if ($rikeCommand) { $rikeCandidates.Add((Split-Path -Parent $rikeCommand.Source)) }
    foreach ($rikeBin in $rikeCandidates) {
        $rikeJava = Join-Path $rikeBin 'java.exe'
        $rikeKeytool = Join-Path $rikeBin 'keytool.exe'
        if (-not ((Test-Path -LiteralPath $rikeJava -PathType Leaf) -and (Test-Path -LiteralPath $rikeKeytool -PathType Leaf))) { continue }
        # java -version writes to stderr; old Windows PowerShell treats that as errors.
        $rikeSavedPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { $rikeVersionText = (& $rikeJava -version 2>&1 | Out-String) }
        finally { $ErrorActionPreference = $rikeSavedPreference }
        if ($rikeVersionText -match 'version\s+"(?<major>\d+)\.') {
            if ([int]$Matches['major'] -ge 17) {
                return @{ Java = $rikeJava; Keytool = $rikeKeytool }
            }
        }
    }
    throw ('没有找到 JDK 17 或更高版本。请安装官方 Temurin JDK，安装后再次双击开始签名.cmd。下载地址：' + $rikeDownloadUrl)
}

# Only non-secret arguments cross the native launcher as ASCII. Official tools
# continue to read passwords from their own interactive stdin prompts.
function Invoke-RikeTool([string]$Java, [string]$Kind, [string]$Tool, [string[]]$Arguments) {
    $rikeJavaArguments = @('--add-exports=java.base/sun.security.tools.keytool=ALL-UNNAMED', 'RikeLocalTool.java', $Kind,
        [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Tool)))
    foreach ($rikeArgument in $Arguments) {
        $rikeJavaArguments += [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($rikeArgument))
    }
    Push-Location -LiteralPath $PSScriptRoot
    try { & $Java @rikeJavaArguments }
    finally { Pop-Location }
}

# Separate command hooks let the synthetic Windows harness exercise the actual
# script while keeping production password prompts inside the official tools.
function Invoke-RikeGenerate([string]$Tool, [string]$Store, [string]$Alias) {
    $rikeJava = Join-Path (Split-Path -Parent $Tool) 'java.exe'
    Invoke-RikeTool $rikeJava 'keytool' $Tool @('-genkeypair','-keystore',$Store,'-storetype','PKCS12','-alias',$Alias,'-keyalg','RSA','-keysize','3072','-sigalg','SHA256withRSA','-validity','10000','-dname','CN=Rike Release') | Out-Host
}
function Invoke-RikeSign([string]$Java, [string]$Tool, [string]$Store, [string]$Alias, [string]$Output, [string]$InputApk) {
    Invoke-RikeTool $Java 'apksigner' $Tool @('sign','--ks',$Store,'--ks-key-alias',$Alias,'--debuggable-apk-permitted','false','--alignment-preserved','true','--v1-signing-enabled','false','--v2-signing-enabled','true','--v3-signing-enabled','true','--v4-signing-enabled','false','--out',$Output,$InputApk) | Out-Host
}
function Invoke-RikeRelease {

try {
    try { $rikeOwnsMutex = $rikeMutex.WaitOne(0) }
    catch [System.Threading.AbandonedMutexException] { $rikeOwnsMutex = $true }
    if (-not $rikeOwnsMutex) { throw '已有签名窗口在运行，请先完成或关闭那个窗口。' }
    Assert-RikeHash $rikeInput $rikeInputHash
    Assert-RikeHash $rikeTool $rikeToolHash
    Assert-RikeHash $rikeBridge $rikeBridgeHash
    $rikeRuntime = Find-RikeJava

    Write-Host ''
    Write-Host '日课 0.6.4 · Windows 本机正式签名' -ForegroundColor Green
    Write-Host '签名不会上传文件；不需要输入日课主密码、恢复密钥或任何记录。'
    Write-Host '这次输入的是安装包签名密钥的保护密码，以后更新仍需使用它。'
    Write-Host ''

    if (-not (Test-Path -LiteralPath $rikeKeystore -PathType Leaf)) {
        $rikeMustRestore = (Test-Path -LiteralPath $rikeIdentityFile) -or (Test-Path -LiteralPath $rikeAliasFile)
        if ($rikeMustRestore) { Write-Host '检测到以前的签名身份但密钥缺失，只能重新选择原密钥；禁止生成另一个身份。' }
        Write-Host '首次准备正式版：'
        Write-Host '  1  本机创建一把新的长期签名密钥（从未发布正式版时选择）'
        Write-Host '  2  使用你已经保管的签名密钥（以前发布过正式版时选择）'
        if ($rikeMustRestore) { $rikeChoice = '2' } else { $rikeChoice = Read-Host '输入 1 或 2' }
        if ($rikeChoice -notin @('1', '2')) { throw '已取消，没有生成或修改签名密钥。' }
        New-Item -ItemType Directory -Path $rikeKeyRoot -Force | Out-Null
        if ($rikeChoice -eq '1') {
            $rikeAlias = 'rike-release'
            $rikePendingKey = Join-Path $rikeKeyRoot ('pending-' + [guid]::NewGuid().ToString('N') + '.p12')
            Write-Host ''
            Write-Host '请设置一个独立的较长英文字符口令，并牢记它。输入时窗口不显示字符。'
            Write-Host '官方工具会要求输入两次；这里不是在设置 APP 主密码。'
            Invoke-RikeGenerate $rikeRuntime.Keytool $rikePendingKey $rikeAlias
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $rikePendingKey -PathType Leaf)) {
                throw '密钥生成未完成。没有替换任何已有密钥。'
            }
            Move-Item -LiteralPath $rikePendingKey -Destination $rikeKeystore
            $rikePendingKey = $null
        } else {
            $rikeExistingKey = (Read-Host '输入已有 .jks、.keystore 或 .p12 文件的完整路径').Trim().Trim('"')
            if (-not (Test-Path -LiteralPath $rikeExistingKey -PathType Leaf)) { throw '找不到已有密钥文件，已停止。' }
            $rikeAlias = (Read-Host '输入原密钥的 alias（别名）').Trim()
            if (-not $rikeAlias) { throw '必须填写原密钥别名；已停止。' }
            # Candidate bytes are never the default until signing and identity checks pass.
            $rikePendingKey = Join-Path $rikeKeyRoot ('candidate-' + [guid]::NewGuid().ToString('N') + '.keystore')
            Copy-Item -LiteralPath $rikeExistingKey -Destination $rikePendingKey
            $rikeSigningKey = $rikePendingKey
            $rikeCandidateImport = $true
        }
        if (-not $rikeCandidateImport) { [IO.File]::WriteAllText($rikeAliasFile, $rikeAlias, [Text.Encoding]::UTF8) }
    } else {
        $rikeAlias = ''
        if (Test-Path -LiteralPath $rikeAliasFile -PathType Leaf) { $rikeAlias = [IO.File]::ReadAllText($rikeAliasFile).Trim() }
        if ($rikeAlias) {
            Write-Host ('已自动选用原签名密钥，别名：' + $rikeAlias)
            if ($CorrectAlias) { $rikeAlias = ''; $rikeAliasChanged = $true }
        }
        if (-not $rikeAlias) {
            $rikeAlias = (Read-Host '输入这把已有密钥的正确 alias（别名）').Trim()
            if (-not $rikeAlias) { throw '未填写别名，原密钥保留。' }
            $rikeAliasChanged = $true
        }
    }

    $rikeOutputRoot = Join-Path $PSScriptRoot 'output'
    New-Item -ItemType Directory -Path $rikeOutputRoot -Force | Out-Null
    $rikeFinalApk = Join-Path $rikeOutputRoot 'rike-0.6.4-release.apk'
    if ((Test-Path -LiteralPath $rikeFinalApk) -or (Test-Path -LiteralPath ($rikeFinalApk + '.txt'))) {
        $rikeFinalApk = Join-Path $rikeOutputRoot ('rike-0.6.4-release-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.apk')
    }
    $rikePendingApk = Join-Path $rikeOutputRoot ('pending-' + [guid]::NewGuid().ToString('N') + '.apk')
    Write-Host ''
    Write-Host '请输入这把原签名密钥的保护密码；随后自动签署、校验并生成 APK。'
    Invoke-RikeSign $rikeRuntime.Java $rikeTool $rikeSigningKey $rikeAlias $rikePendingApk $rikeInput
    if ($LASTEXITCODE -ne 0) { throw '签名失败，请核对签名密码后重试；需要修正别名时双击“修正密钥别名.cmd”。密钥和原安装包仍保留。' }

    $rikeVerifyLines = @(Invoke-RikeTool $rikeRuntime.Java 'apksigner' $rikeTool @('verify','--verbose','--print-certs',$rikePendingApk))
    if ($LASTEXITCODE -ne 0) { throw '签名验证失败，没有交付这个安装包。' }
    $rikeVerifyText = $rikeVerifyLines -join [Environment]::NewLine
    $rikeCertificates = @([regex]::Matches($rikeVerifyText, '(?m)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$'))
    if ($rikeCertificates.Count -ne 1) { throw '没有取得唯一的签名证书指纹，已停止。' }
    $rikeFingerprint = $rikeCertificates[0].Groups[1].Value.ToLowerInvariant()
    if (Test-Path -LiteralPath $rikeIdentityFile -PathType Leaf) {
        if ([IO.File]::ReadAllText($rikeIdentityFile).Trim().ToLowerInvariant() -ne $rikeFingerprint) {
            throw '签名身份与以前不一致。请恢复原签名密钥；当前安装包不会交付。'
        }
    }
    if (($rikeVerifyText -notmatch '(?m)^Verified using v2 scheme.*true\s*$') -or
        ($rikeVerifyText -notmatch '(?m)^Verified using v3 scheme.*true\s*$')) {
        throw 'APK v2/v3 签名未验证通过，已停止。'
    }
    # Verify first, then persist the public identity before publishing any APK.
    [IO.File]::WriteAllText($rikeIdentityFile, $rikeFingerprint, [Text.Encoding]::UTF8)
    if ($rikeCandidateImport) {
        Move-Item -LiteralPath $rikePendingKey -Destination $rikeKeystore
        $rikePendingKey = $null
        [IO.File]::WriteAllText($rikeAliasFile, $rikeAlias, [Text.Encoding]::UTF8)
    } elseif ($rikeAliasChanged) {
        [IO.File]::WriteAllText($rikeAliasFile, $rikeAlias, [Text.Encoding]::UTF8)
    }
    $rikeReceipt = @(
        'Rike 0.6.4 release / versionCode 17',
        ('Package: ' + $rikePackage),
        ('APK: ' + [IO.Path]::GetFileName($rikeFinalApk)),
        ('APK SHA-256: ' + (Get-FileHash -LiteralPath $rikePendingApk -Algorithm SHA256).Hash.ToLowerInvariant()),
        ('Signer certificate SHA-256: ' + $rikeFingerprint),
        'Source: Android 0.6.4; exact build metadata in BUILD-INFO.json',
        'Release build: no INTERNET permission, no debuggable flag; input hash pinned.',
        'Passwords and private keys are not included in this receipt.',
        '',
        $rikeVerifyText
    ) -join [Environment]::NewLine
    $rikePendingReceipt = Join-Path $rikeOutputRoot ('pending-' + [guid]::NewGuid().ToString('N') + '.txt')
    [IO.File]::WriteAllText($rikePendingReceipt, $rikeReceipt, [Text.Encoding]::UTF8)
    Move-Item -LiteralPath $rikePendingReceipt -Destination ($rikeFinalApk + '.txt')
    $rikePendingReceipt = $null
    Move-Item -LiteralPath $rikePendingApk -Destination $rikeFinalApk
    $rikePendingApk = $null

    Write-Host ''
    Write-Host '正式签名与验证完成。' -ForegroundColor Green
    Write-Host ('可安装 APK：' + $rikeFinalApk)
    Write-Host ('签名密钥目录：' + $rikeKeyRoot)
    Write-Host '请把密钥目录里的三个文件备份到离线介质，保护密码另行保管。'
    Write-Host '以后沿用这些文件，不要重新生成密钥，也不要把密钥或密码发给 AI。'
    Write-Host '将已签名 APK 传到手机，直接覆盖安装已有正式版；不要先卸载。'
    return $true
} catch {
    Write-Host ''
    Write-Host ('尚未交付可安装 APK：' + $_.Exception.Message) -ForegroundColor Yellow
    return $false
} finally {
    if ($rikePendingApk -and (Test-Path -LiteralPath $rikePendingApk)) {
        Remove-Item -LiteralPath $rikePendingApk -Force -ErrorAction SilentlyContinue
    }
    if ($rikePendingKey -and (Test-Path -LiteralPath $rikePendingKey)) {
        Remove-Item -LiteralPath $rikePendingKey -Force -ErrorAction SilentlyContinue
    }
    if ($rikePendingReceipt -and (Test-Path -LiteralPath $rikePendingReceipt)) { Remove-Item -LiteralPath $rikePendingReceipt -Force -ErrorAction SilentlyContinue }
    if ($rikeOwnsMutex) { $rikeMutex.ReleaseMutex() }
    $rikeMutex.Dispose()
}

}
# Dot-sourcing loads the workflow without running it; normal double-click runs it.
if ($MyInvocation.InvocationName -ne '.') {
    if (Invoke-RikeRelease) { exit 0 } else { exit 1 }
}
