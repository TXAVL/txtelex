<#
.SYNOPSIS
  Build nhanh TxTelex theo target — không cần Android Studio.

.EXAMPLE
  .\build.ps1                 # = debug
  .\build.ps1 release         # APK release đã ký
  .\build.ps1 aab             # AAB nộp Google Play
  .\build.ps1 all             # test + debug + release + aab
  .\build.ps1 run             # build debug, cài lên máy đang cắm USB, mở app
  .\build.ps1 install release # cài bản release lên máy
  .\build.ps1 test            # chạy toàn bộ unit test
  .\build.ps1 key-password    # hiện mật khẩu keystore cất trong Windows Credential Manager

  Kết quả chép vào .\dist\ dạng txtelex-<phiên bản>-<loại>.apk/.aab
#>
param(
    [ValidateSet('debug', 'release', 'aab', 'all', 'test', 'install', 'run', 'clean', 'dist', 'key-password', 'help')]
    [string]$Target = 'debug',
    [ValidateSet('debug', 'release')]
    [string]$Variant = 'debug'
)

# Continue: lệnh native (apksigner, adb) in ra stderr là chuyện thường — PS 5.1 coi là lỗi nếu Stop.
# Lỗi thật được kiểm tra bằng $LASTEXITCODE / Test-Path rồi throw.
$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
Set-Location $root

# ── Môi trường ──────────────────────────────────────────────────────────────
# PATH của máy có mục lỗi `C:\Program Files\dotnet"` làm worker test của Gradle không chạy được.
$env:PATH = ($env:PATH -split ';' | Where-Object { $_ -notmatch '"' }) -join ';'
if ($Target -ne 'help' -and $Target -ne 'key-password') {
    if (-not (Test-Path 'local.properties')) { throw 'Chưa cài môi trường build — chạy  .\setup.cmd  một lần trước (xem README).' }
}
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jdk = foreach ($base in 'C:\Program Files\Microsoft', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java', 'C:\Program Files\Zulu', 'C:\Program Files\Amazon Corretto') {
        Get-ChildItem $base -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'jdk-?17|zulu.*17|^17' -and (Test-Path "$($_.FullName)\bin\java.exe") }
    }
    $jdk = $jdk | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName } else { throw 'Không tìm thấy JDK 17 — chạy  .\setup.cmd  (hoặc: winget install -e --id Microsoft.OpenJDK.17)' }
}
# SDK: ưu tiên sdk.dir trong local.properties (setup.cmd ghi), rồi ANDROID_HOME, rồi C:\Android.
$sdkDir = if (Test-Path 'local.properties') {
    (Get-Content 'local.properties' | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1) -replace '^sdk\.dir=', '' -replace '\\:', ':' -replace '\\\\', '\'
}
if (-not $sdkDir) { $sdkDir = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'C:\Android' } }
$env:ANDROID_HOME = $sdkDir
$adb = Join-Path $sdkDir 'platform-tools\adb.exe'
$apksigner = Get-ChildItem (Join-Path $sdkDir 'build-tools') -Directory -ErrorAction SilentlyContinue |
    Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } -Descending |
    ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } | Where-Object { Test-Path $_ } | Select-Object -First 1

$version = (Select-String -Path 'app\build.gradle.kts' -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
$pkg = 'nm.txa.telex'
$dist = Join-Path $root 'dist'

function Step($msg) { Write-Host "`n▶ $msg" -ForegroundColor Cyan }
function Ok($msg) { Write-Host "✔ $msg" -ForegroundColor Green }

function Gradle([string[]]$tasks) {
    Step "gradlew $($tasks -join ' ')"
    & .\gradlew.bat @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle lỗi (mã $LASTEXITCODE)" }
}

function Collect([string]$kind) {
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    $src, $ext = switch ($kind) {
        'debug'   { 'app\build\outputs\apk\debug\app-debug.apk', 'apk' }
        'release' { 'app\build\outputs\apk\release\app-release.apk', 'apk' }
        'aab'     { 'app\build\outputs\bundle\release\app-release.aab', 'aab' }
    }
    $suffix = $kind
    # Không có keystore.properties ⇒ Gradle xuất app-release-unsigned.apk
    if ($kind -eq 'release' -and -not (Test-Path $src)) { $src = 'app\build\outputs\apk\release\app-release-unsigned.apk'; $suffix = 'release-unsigned' }
    if (-not (Test-Path $src)) { throw "Không thấy $src" }
    $name = if ($kind -eq 'aab') { "txtelex-$version.aab" } else { "txtelex-$version-$suffix.apk" }
    $out = Join-Path $dist $name
    Copy-Item $src $out -Force
    $mb = '{0:N1}' -f ((Get-Item $out).Length / 1MB)
    Ok "$name  ($mb MB)"
    return $out
}

function RequireSigning {
    if (-not (Test-Path 'keystore.properties')) {
        Write-Warning 'Thiếu keystore.properties ⇒ bản release sẽ KHÔNG được ký (không cài / nộp Play được). Xem README › Ký bản release; mẫu: keystore.properties.example'
    }
}

function VerifySig([string]$apk) {
    if ($apksigner -and (Test-Path $apksigner)) {
        $dn = & $apksigner verify --print-certs $apk 2>&1 | Select-String 'certificate DN' | Select-Object -First 1
        if ($dn) { Ok "Chữ ký: $($dn.Line.Split(':', 2)[1].Trim())" } else { Write-Warning 'APK chưa được ký' }
    }
}

function Device {
    if (-not (Test-Path $adb)) { throw "Không thấy adb ở $adb" }
    $lines = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' }
    if (-not $lines) { throw 'Không có máy nào — cắm USB và bật USB Debugging (Tuỳ chọn nhà phát triển).' }
    Ok "Máy: $(($lines | Select-Object -First 1).Split("`t")[0])"
}

function Install([string]$apk) {
    Device
    Step "adb install -r $(Split-Path $apk -Leaf)"
    & $adb install -r $apk
    if ($LASTEXITCODE -ne 0) {
        Write-Warning 'Cài lỗi. Nếu báo INSTALL_FAILED_UPDATE_INCOMPATIBLE: máy đang có bản ký khoá khác (debug ↔ release) — gỡ app cũ rồi chạy lại.'
        throw 'adb install thất bại'
    }
    Ok 'Đã cài'
}

# ── Windows Credential Manager (mật khẩu keystore) ─────────────────────────
function Read-StoredPassword([string]$target) {
    Add-Type -Namespace TxaCred -Name Native -MemberDefinition @'
[System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential, CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
public struct CREDENTIAL { public int Flags; public int Type; public string TargetName; public string Comment;
  public long LastWritten; public int CredentialBlobSize; public System.IntPtr CredentialBlob; public int Persist;
  public int AttributeCount; public System.IntPtr Attributes; public string TargetAlias; public string UserName; }
[System.Runtime.InteropServices.DllImport("advapi32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode, SetLastError = true)]
public static extern bool CredRead(string target, int type, int flags, out System.IntPtr cred);
[System.Runtime.InteropServices.DllImport("advapi32.dll")]
public static extern void CredFree(System.IntPtr cred);
'@ -ErrorAction SilentlyContinue
    $p = [IntPtr]::Zero
    if (-not [TxaCred.Native]::CredRead($target, 1, 0, [ref]$p)) { return $null }
    try {
        $c = [Runtime.InteropServices.Marshal]::PtrToStructure($p, [type][TxaCred.Native+CREDENTIAL])
        return [Runtime.InteropServices.Marshal]::PtrToStringUni($c.CredentialBlob, $c.CredentialBlobSize / 2)
    } finally { [TxaCred.Native]::CredFree($p) }
}

# ── Target ─────────────────────────────────────────────────────────────────
Write-Host "TxTelex $version · target: $Target" -ForegroundColor Magenta
$sw = [Diagnostics.Stopwatch]::StartNew()
switch ($Target) {
    'help'    { Get-Help $PSCommandPath -Detailed; return }
    'clean'   { Gradle @('clean'); if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }; Ok 'Đã dọn' }
    'test'    { Gradle @(':app:testDebugUnitTest'); Ok 'Toàn bộ test qua' }
    'debug'   { Gradle @('assembleDebug'); Collect 'debug' | Out-Null }
    'release' { RequireSigning; Gradle @('assembleRelease'); VerifySig (Collect 'release') }
    'aab'     { RequireSigning; Gradle @('bundleRelease'); Collect 'aab' | Out-Null }
    'all'     {
        RequireSigning
        Gradle @(':app:testDebugUnitTest', 'assembleDebug', 'assembleRelease', 'bundleRelease')
        Collect 'debug' | Out-Null; VerifySig (Collect 'release'); Collect 'aab' | Out-Null
    }
    'dist'    { Collect 'debug' | Out-Null; if (Test-Path 'app\build\outputs\apk\release\app-release.apk') { Collect 'release' | Out-Null } }
    'install' {
        if ($Variant -eq 'release') { RequireSigning; Gradle @('assembleRelease'); Install (Collect 'release') }
        else { Gradle @('assembleDebug'); Install (Collect 'debug') }
    }
    'run'     {
        Gradle @('assembleDebug'); Install (Collect 'debug')
        & $adb shell am start -n "$pkg/com.customkey.vietime.ui.MainActivity" | Out-Null
        Ok 'Đã mở TxTelex trên máy'
    }
    'key-password' {
        $pw = Read-StoredPassword 'TxTelex/release-keystore'
        if ($pw) { Write-Host "Mật khẩu keystore (alias txtelex): $pw" -ForegroundColor Yellow }
        else { Write-Warning 'Chưa có trong Credential Manager. Thêm: cmdkey /generic:TxTelex/release-keystore /user:txtelex /pass:<mật khẩu>' }
        Write-Host 'Keystore: C:\Users\admin\keystores\txtelex-release.jks'
    }
}
Ok ("Xong sau {0:N0}s" -f $sw.Elapsed.TotalSeconds)
