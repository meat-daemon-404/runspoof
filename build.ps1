<#
.SYNOPSIS
    RunSpoof 一键构建脚本（Windows / PowerShell 5.1+）

.DESCRIPTION
    从源码编译出可直接安装的 RunSpoof APK。全流程：
      javac  → d8  → aapt2 link  → jar uf 塞 META-INF/xposed  → zipalign → apksigner

.PARAMETER Sdk
    Android SDK 根目录（需含 platforms/android-34 与 build-tools/34.0.0）

.PARAMETER Jdk
    JDK 17 根目录

.PARAMETER ApiJar
    libxposed API 102 的 classes.jar

.PARAMETER OutDir
    产物目录，默认本仓库同级 dist/

.PARAMETER VersionCode
    版本号（整数），默认 10

.PARAMETER VersionName
    版本名，默认 1.7

.PARAMETER NoSign
    只出未签名对齐包，跳过签名

.EXAMPLE
    .\build.ps1 -Sdk 'D:\Project\toolchain\android-sdk' -Jdk 'D:\Project\toolchain\jdk17' -ApiJar 'D:\_tools\lsposed-dl\api102\classes.jar'
#>

[CmdletBinding()]
param(
    [string]$Sdk = $env:ANDROID_SDK_ROOT,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$ApiJar,
    [string]$OutDir,
    [int]$VersionCode = 41,
    [string]$VersionName = '3.3',
    [switch]$NoSign,
    [string]$Keystore,
    [string]$KsAlias = 'runspoof',
    [string]$KsPass = 'runspoof'
)

# 注意：这里必须是 Continue。javac / keytool / apksigner 都会往 stderr 写正常提示
# （deprecation 警告、"正在为以下对象生成..."），在 Stop 模式下 PowerShell 会把
# 原生程序的 stderr 当成 ErrorRecord 直接终止脚本，导致构建莫名其妙中断。
$ErrorActionPreference = 'Continue'
$Root = $PSScriptRoot
$Build = Join-Path $Root 'build'
if (-not $OutDir) { $OutDir = Join-Path $Root 'dist' }

# ────────────────────────────────────────────── 0. 参数检查
function Fail($msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red; exit 1 }

if (-not $Sdk) { $Sdk = 'D:\Project\toolchain\android-sdk' }
if (-not $Jdk) { $Jdk = 'D:\Project\toolchain\jdk17' }
if (-not $ApiJar) { $ApiJar = 'D:\校园跑\_tools\lsposed-dl\api102\classes.jar' }

$Javac = Join-Path $Jdk 'bin\javac.exe'
$Jar   = Join-Path $Jdk 'bin\jar.exe'
$Bt    = Join-Path $Sdk 'build-tools\34.0.0'
$AJ    = Join-Path $Sdk 'platforms\android-34\android.jar'

foreach ($p in @($Javac, $Jar, (Join-Path $Bt 'd8.bat'), (Join-Path $Bt 'aapt2.exe'),
                 (Join-Path $Bt 'zipalign.exe'), (Join-Path $Bt 'apksigner.bat'), $AJ, $ApiJar)) {
    if (-not (Test-Path $p)) { Fail "缺少依赖：$p`n用 -Sdk / -Jdk / -ApiJar 指定正确路径。" }
}

$env:JAVA_HOME = $Jdk
$env:PATH = (Join-Path $Jdk 'bin') + ';' + $env:PATH

Write-Host "==> JDK     : $Jdk"
Write-Host "==> SDK     : $Sdk"
Write-Host "==> API jar : $ApiJar"
Write-Host ""

# ────────────────────────────────────────────── 1. javac
$Src  = Join-Path $Root 'src'
$Cls  = Join-Path $Build 'classes'
$Dex  = Join-Path $Build 'dex'
$Stage = Join-Path $Build 'stage'
$Out  = Join-Path $Build 'out'

foreach ($d in @($Cls, $Dex, $Stage, $Out)) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

$Sources = Get-ChildItem $Src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if (-not $Sources) { Fail "src 下没找到 .java" }

Write-Host "[1/6] javac ..."
# javac 的 deprecation 提示走 stderr，PowerShell 会把它包成 NativeCommandError。
# 这里直接丢弃 stderr，成败完全看退出码。
& $Javac --release 11 -encoding UTF-8 -nowarn -cp "$AJ;$ApiJar" -d $Cls $Sources 2>$null
if ($LASTEXITCODE -ne 0) { Fail "javac 失败（rc=$LASTEXITCODE）" }
$nCls = (Get-ChildItem $Cls -Recurse -Filter *.class).Count
Write-Host "      生成 $nCls 个 .class"

# ────────────────────────────────────────────── 2. d8
Write-Host "[2/6] d8 ..."
$ClassFiles = Get-ChildItem $Cls -Recurse -Filter *.class | ForEach-Object { $_.FullName }
# ★ 不要加 --release。
# d8 的 --release 会开启额外的优化（常量折叠、去调试信息等），产出的 dex
# 与不带该参数时不同。本项目在真机上验证通过的那份 APK 是不带 --release
# 构建的；为了让「从仓库构建的结果 == 已验证的结果」，这里必须保持一致。
& (Join-Path $Bt 'd8.bat') --min-api 26 --lib $AJ --lib $ApiJar --output $Dex @ClassFiles 2>$null
if ($LASTEXITCODE -ne 0 -or -not (Test-Path (Join-Path $Dex 'classes.dex'))) { Fail "d8 失败" }
$dexSize = (Get-Item (Join-Path $Dex 'classes.dex')).Length
Write-Host "      classes.dex = $dexSize B"

# ────────────────────────────────────────────── 3. aapt2 link（资源 + manifest）
Write-Host "[3/6] aapt2 link ..."
$BaseApk = Join-Path $Out 'base.apk'
& (Join-Path $Bt 'aapt2.exe') link -o $BaseApk -I $AJ `
    --manifest (Join-Path $Root 'AndroidManifest.xml') `
    --min-sdk-version 26 --target-sdk-version 34 `
    --version-code $VersionCode --version-name $VersionName 2>$null
if ($LASTEXITCODE -ne 0) { Fail "aapt2 link 失败" }

# ────────────────────────────────────────────── 4. 塞 dex + META-INF/xposed
#   ★ 关键：绝不能用 aapt2 -A assets 打包 META-INF/xposed
#     aapt2 在 Windows 上会把条目名写成反斜杠（META-INF\xposed\module.prop），
#     LSPosed 按正斜杠查找，于是永远"模块没声明"，模块不生效。
#     用 jar uf 进去的条目是标准正斜杠，才能被识别。
Write-Host "[4/6] 打包 classes.dex + META-INF/xposed ..."

Copy-Item (Join-Path $Dex 'classes.dex') (Join-Path $Stage 'classes.dex') -Force

$XposedDir = Join-Path $Stage 'META-INF\xposed'
New-Item -ItemType Directory -Force -Path $XposedDir | Out-Null
$XposedSrc = Join-Path $Root 'assets\META-INF\xposed'
foreach ($f in @('module.prop', 'scope.list', 'java_init.list')) {
    $from = Join-Path $XposedSrc $f
    if (-not (Test-Path $from)) { Fail "缺少 $from" }
    Copy-Item $from (Join-Path $XposedDir $f) -Force
}

Push-Location $Stage
try {
    & $Jar uf $BaseApk classes.dex 2>&1 | Out-Null
    & $Jar uf $BaseApk 'META-INF\xposed\module.prop' 'META-INF\xposed\scope.list' 'META-INF\xposed\java_init.list' 2>&1 | Out-Null
} finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { Fail "jar uf 失败" }

# ────────────────────────────────────────────── 5. zipalign
Write-Host "[5/6] zipalign ..."
$Aligned = Join-Path $Out 'aligned.apk'
& (Join-Path $Bt 'zipalign.exe') -f -p 4 $BaseApk $Aligned
if ($LASTEXITCODE -ne 0) { Fail "zipalign 失败" }

# ────────────────────────────────────────────── 6. 签名
$Final = Join-Path $OutDir "RunSpoof-v$VersionCode.apk"
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force -Path $OutDir | Out-Null }
if ($NoSign) {
    Copy-Item $Aligned $Final -Force
    Write-Host "[6/6] 跳过签名（-NoSign），产物未签名"
} else {
    Write-Host "[6/6] apksigner ..."
    if (-not $Keystore) {
        # 没有现成 keystore 就生成一个仅供本地调试使用的。
        # 放在 build/ 下（被 .gitignore 忽略），避免误提交。
        $Keystore = Join-Path $Build 'debug-keystore.p12'
        if (-not (Test-Path $Keystore)) {
            Write-Host "      未指定 -Keystore，生成临时调试密钥库：$Keystore"
            & (Join-Path $Jdk 'bin\keytool.exe') -genkeypair -v `
                -keystore $Keystore -storetype PKCS12 `
                -alias $KsAlias -keyalg RSA -keysize 2048 -validity 10000 `
                -storepass $KsPass -keypass $KsPass `
                -dname "CN=RunSpoof Debug, OU=Dev, O=Local, L=Local, S=Local, C=CN" 2>$null
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $Keystore)) { Fail "keytool 生成密钥库失败" }
        } else {
            Write-Host "      复用已有调试密钥库：$Keystore"
        }
    }
    & (Join-Path $Bt 'apksigner.bat') sign `
        --ks $Keystore --ks-type PKCS12 --ks-key-alias $KsAlias `
        --ks-pass "pass:$KsPass" --key-pass "pass:$KsPass" `
        --min-sdk-version 26 `
        --out $Final $Aligned 2>$null
    if ($LASTEXITCODE -ne 0) { Fail "apksigner 失败" }
    & (Join-Path $Bt 'apksigner.bat') verify $Final 2>$null
    if ($LASTEXITCODE -ne 0) { Fail "apksigner verify 失败" }
}

# ────────────────────────────────────────────── 校验 & 报告
$size = (Get-Item $Final).Length
Write-Host ""
Write-Host "==> 产物：$Final ($size B)" -ForegroundColor Green

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($Final)
$names = $zip.Entries | ForEach-Object { $_.FullName }
$zip.Dispose()

Write-Host "==> APK 条目："
$names | ForEach-Object { Write-Host "      $_" }

$required = @('META-INF/xposed/module.prop', 'META-INF/xposed/scope.list',
              'META-INF/xposed/java_init.list', 'classes.dex', 'AndroidManifest.xml')
$missing = $required | Where-Object { $names -notcontains $_ }
if ($missing) {
    Write-Host "[FAIL] APK 缺少必需条目：$($missing -join ', ')" -ForegroundColor Red
    Write-Host "       最常见原因：用 aapt2 -A 打包了 assets，条目名变成反斜杠。" -ForegroundColor Yellow
    exit 1
}
Write-Host "==> 校验通过：META-INF/xposed 在 APK 根目录，正斜杠命名。" -ForegroundColor Green
