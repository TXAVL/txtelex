<#
.SYNOPSIS
  TxTelex Build — build / cài / kiểm tra môi trường / chuyển keystore, không cần Android Studio.

.EXAMPLE
  .\build.cmd                    # không tham số ⇒ menu chọn số
  .\build.cmd debug              # APK debug
  .\build.cmd release            # APK release đã ký
  .\build.cmd aab                # AAB nộp Google Play
  .\build.cmd all                # test + debug + release + aab
  .\build.cmd test               # unit test
  .\build.cmd run                # build debug, cài lên máy qua USB, mở app
  .\build.cmd install release    # cài bản release lên máy
  .\build.cmd doctor             # kiểm tra môi trường build
  .\build.cmd keystore-export    # gói keystore + mật khẩu thành 1 file mã hoá AES-256
  .\build.cmd keystore-import -Path D:\txtelex-keystore.txaks
  .\build.cmd key-password       # xem mật khẩu keystore (Windows Credential Manager)
  .\build.cmd clean
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('', 'debug', 'release', 'aab', 'all', 'test', 'install', 'run', 'clean', 'doctor',
        'keystore-export', 'keystore-import', 'key-password', 'menu', 'help')]
    [string]$Target = '',
    [Parameter(Position = 1)]
    [ValidateSet('debug', 'release')]
    [string]$Variant = 'debug',
    [string]$Path
)

# Continue: lệnh native (apksigner, adb, keytool) in ra stderr là chuyện thường — PS 5.1 coi là lỗi nếu Stop.
# Lỗi thật được kiểm tra bằng $LASTEXITCODE / Test-Path rồi throw.
$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
Set-Location $root
. (Join-Path $root 'scripts\txa-ui.ps1')

$PKG = 'nm.txa.telex'
$CRED_TARGET = 'TxTelex/release-keystore'
$dist = Join-Path $root 'dist'
$logDir = Join-Path $root 'build\txa-logs'
$version = (Select-String -Path 'app\build.gradle.kts' -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
$versionCode = (Select-String -Path 'app\build.gradle.kts' -Pattern 'versionCode = (\d+)').Matches[0].Groups[1].Value

class TxaFail : Exception {
    [string[]]$Hints
    TxaFail([string]$msg, [string[]]$hints) : base($msg) { $this.Hints = $hints }
}
function Fail([string]$msg, [string[]]$hints = @()) { throw [TxaFail]::new($msg, $hints) }

# ── Môi trường ──────────────────────────────────────────────────────────────
# PATH một số máy có mục lỗi chứa dấu " (vd: C:\Program Files\dotnet") làm Java/Gradle hỏng.
$env:PATH = ($env:PATH -split ';' | Where-Object { $_ -and $_ -notmatch '"' }) -join ';'

function Find-Jdk17 {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        $v = (cmd /c "`"$env:JAVA_HOME\bin\java.exe`" -version 2>&1" | Select-Object -First 1) -as [string]
        if ($v -match '"17\.') { return $env:JAVA_HOME }
    }
    foreach ($base in 'C:\Program Files\Microsoft', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java', 'C:\Program Files\Zulu', 'C:\Program Files\Amazon Corretto') {
        $hit = Get-ChildItem $base -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match 'jdk-?17|zulu.*17|^17' -and (Test-Path "$($_.FullName)\bin\java.exe") } | Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    return $null
}

function Get-SdkDir {
    if (Test-Path 'local.properties') {
        $l = Get-Content 'local.properties' | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($l) { return ($l -replace '^sdk\.dir=', '' -replace '\\:', ':' -replace '\\\\', '\') }
    }
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
    return 'C:\Android'
}

$jdk = Find-Jdk17
if ($jdk) { $env:JAVA_HOME = $jdk }
$sdkDir = Get-SdkDir
$env:ANDROID_HOME = $sdkDir
$adb = Join-Path $sdkDir 'platform-tools\adb.exe'
$keytool = if ($jdk) { Join-Path $jdk 'bin\keytool.exe' } else { $null }
$apksigner = Get-ChildItem (Join-Path $sdkDir 'build-tools') -Directory -ErrorAction SilentlyContinue |
    Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } -Descending |
    ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } | Where-Object { Test-Path $_ } | Select-Object -First 1

function Require-Env {
    if (-not $jdk) { Fail 'Không tìm thấy JDK 17.' @('Chạy  .\setup.cmd  (tự cài), hoặc: winget install -e --id Microsoft.OpenJDK.17') }
    if (-not (Test-Path 'local.properties')) { Fail 'Chưa cài môi trường build (thiếu local.properties).' @('Chạy  .\setup.cmd  một lần trên máy này.') }
    if (-not (Test-Path (Join-Path $sdkDir 'platforms\android-35'))) { Fail "Android SDK ở $sdkDir chưa đủ gói." @('Chạy lại  .\setup.cmd') }
}

# ── keystore.properties ────────────────────────────────────────────────────
function Read-KeystoreProps {
    if (-not (Test-Path 'keystore.properties')) { return $null }
    $h = @{}
    foreach ($l in Get-Content 'keystore.properties') {
        if ($l -match '^\s*([A-Za-z]+)\s*=\s*(.*)$') { $h[$Matches[1]] = $Matches[2].Trim() }
    }
    return $h
}

function Test-Signing([switch]$Quiet) {
    $p = Read-KeystoreProps
    if (-not $p) {
        if (-not $Quiet) { Ui-Warn 'Thiếu keystore.properties ⇒ bản release KHÔNG được ký (không cài / nộp Play được).'; Ui-Info 'Nhập keystore:  .\build.cmd keystore-import -Path <file .txaks>   ·  mẫu: keystore.properties.example' }
        return $false
    }
    $f = $p['storeFile'] -replace '/', '\'
    if (-not (Test-Path $f)) { Fail "Không thấy file keystore: $f" @('Sửa storeFile trong keystore.properties, hoặc chạy  .\build.cmd keystore-import') }
    return $true
}

# ── Gradle + sản phẩm ──────────────────────────────────────────────────────
function Run-Gradle([string[]]$tasks, [string]$label) {
    Ui-Section "$label"
    Ui-Info ("gradlew " + ($tasks -join ' '))
    $log = Join-Path $logDir ("{0}-{1}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), ($label -replace '[^\w]+', '_'))
    $r = Invoke-TxaGradle $tasks $log
    if (-not $r.Ok) { Show-TxaFailure $r $root; throw [TxaFail]::new('__shown__', @()) }
}

$script:artifacts = @()
function Collect([string]$kind) {
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    $suffix = $kind
    $src = switch ($kind) {
        'debug'   { 'app\build\outputs\apk\debug\app-debug.apk' }
        'release' { 'app\build\outputs\apk\release\app-release.apk' }
        'aab'     { 'app\build\outputs\bundle\release\app-release.aab' }
    }
    # Không có keystore.properties ⇒ Gradle xuất app-release-unsigned.apk
    if ($kind -eq 'release' -and -not (Test-Path $src)) { $src = 'app\build\outputs\apk\release\app-release-unsigned.apk'; $suffix = 'release-unsigned' }
    if (-not (Test-Path $src)) { Fail "Gradle báo xong nhưng không thấy $src" @('Chạy  .\build.cmd clean  rồi build lại.') }
    $name = if ($kind -eq 'aab') { "txtelex-$version.aab" } else { "txtelex-$version-$suffix.apk" }
    $out = Join-Path $dist $name
    Copy-Item $src $out -Force
    $sig = Get-Signer $out $kind
    $script:artifacts += [pscustomobject]@{
        File = $name
        Size = '{0:N1} MB' -f ((Get-Item $out).Length / 1MB)
        'SHA-256' = (Get-FileHash $out -Algorithm SHA256).Hash.Substring(0, 16).ToLower() + '…'
        'Ký bởi' = $sig
    }
    return $out
}

function Get-Signer([string]$file, [string]$kind) {
    if ($kind -eq 'debug') { return '✔ debug key' }
    if ($kind -eq 'aab') {
        $out = & $keytool -printcert -jarfile $file 2>&1 | Out-String
        if ($out -match 'Owner: .*?\bO=([^,\r\n]+)') { return "✔ $($Matches[1].Trim())" }
        return '▲ chưa ký'
    }
    if (-not $apksigner) { return '?' }
    $dn = & $apksigner verify --print-certs $file 2>&1 | Select-String 'certificate DN' | Select-Object -First 1
    if ($dn -and "$dn" -match 'O=([^,]+)') { return "✔ $($Matches[1].Trim())" }
    return '▲ chưa ký'
}

function Show-Artifacts {
    if (-not $script:artifacts) { return }
    Ui-Section "Kết quả  →  $dist"
    Ui-Table $script:artifacts @('File', 'Size', 'SHA-256', 'Ký bởi')
}

# ── Thiết bị ───────────────────────────────────────────────────────────────
function Get-Device {
    if (-not (Test-Path $adb)) { Fail "Không thấy adb ở $adb" @('Chạy  .\setup.cmd') }
    $lines = & $adb devices 2>&1 | Select-Object -Skip 1 | Where-Object { "$_".Trim() }
    $ok = $lines | Where-Object { "$_" -match '\tdevice$' } | Select-Object -First 1
    if ($ok) {
        $id = "$ok".Split("`t")[0]
        $model = (& $adb -s $id shell getprop ro.product.model 2>$null | Out-String).Trim()
        Ui-Ok "Máy: $model ($id)"
        return $id
    }
    if ($lines | Where-Object { "$_" -match 'unauthorized' }) { Fail 'Máy chưa cho phép gỡ lỗi USB.' @('Mở khoá điện thoại, chọn "Cho phép gỡ lỗi USB" trong hộp thoại, rồi chạy lại.') }
    if ($lines | Where-Object { "$_" -match 'offline' }) { Fail 'Máy đang offline với adb.' @('Rút cáp cắm lại, hoặc chạy:  adb kill-server') }
    Fail 'Không thấy điện thoại nào.' @('Cắm USB, bật Tuỳ chọn nhà phát triển › Gỡ lỗi USB.', 'Máy vẫn không hiện: cài driver https://developer.android.com/studio/run/oem-usb')
}

function Install-Apk([string]$apk) {
    $id = Get-Device
    Ui-Section "Cài lên máy"
    $out = & $adb -s $id install -r $apk 2>&1 | Out-String
    if ($out -match 'Success') { Ui-Ok "Đã cài $(Split-Path $apk -Leaf)"; return $id }
    $hint = switch -Regex ($out) {
        'INSTALL_FAILED_UPDATE_INCOMPATIBLE' { 'Máy đang có TxTelex ký bằng khoá khác (debug ↔ release). Gỡ app cũ:  adb uninstall nm.txa.telex  rồi chạy lại.' }
        'INSTALL_FAILED_INSUFFICIENT_STORAGE' { 'Điện thoại hết dung lượng.' }
        'INSTALL_FAILED_VERSION_DOWNGRADE' { 'Máy đang có bản mới hơn. Gỡ app cũ rồi cài lại.' }
        'INSTALL_PARSE_FAILED_NO_CERTIFICATES' { 'APK chưa ký — cần keystore.properties (xem  .\build.cmd doctor).' }
        'INSTALL_FAILED_USER_RESTRICTED|INSTALL_CANCELED_BY_USER' { 'Điện thoại chặn cài qua USB — bật "Cài qua USB" trong Tuỳ chọn nhà phát triển (Xiaomi/Oppo).' }
        default { 'Xem thông báo của adb ở trên.' }
    }
    Ui-Box 'CÀI LỖI' (@($out.Trim() -split "`n" | Select-Object -Last 3 | ForEach-Object { $_.Trim() }) + @('', "→ $hint")) 'error'
    throw [TxaFail]::new('__shown__', @())
}

# ── Windows Credential Manager ─────────────────────────────────────────────
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

# ── Gói keystore mã hoá (.txaks) ───────────────────────────────────────────
# Định dạng: "TXAKS1\n" | vòng lặp PBKDF2 (int32 LE) | salt 16 | IV 16 | bản mã AES-256-CBC | HMAC-SHA256 32
# Khoá = PBKDF2-SHA256(passphrase, salt, 300 000 vòng) → 64 byte: 32 mã hoá + 32 HMAC (encrypt-then-MAC).
$KS_MAGIC = [Text.Encoding]::ASCII.GetBytes("TXAKS1`n")

function Get-Passphrase([string]$prompt, [switch]$Confirm) {
    if ($env:TXA_BUNDLE_PASSPHRASE) { return $env:TXA_BUNDLE_PASSPHRASE }
    if ([Console]::IsInputRedirected) { Fail 'Cần nhập passphrase nhưng không có bàn phím.' @('Chạy lệnh này trong cửa sổ PowerShell / CMD bình thường.') }
    $s = Read-Host "  $prompt" -AsSecureString
    $p = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))
    if ($Confirm) {
        if ($p.Length -lt 10) { Fail 'Passphrase quá ngắn (cần ≥ 10 ký tự).' @('Dùng một câu dễ nhớ, vd:  TxTelex-may-moi-2027!') }
        $s2 = Read-Host '  Nhập lại passphrase' -AsSecureString
        $p2 = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s2))
        if ($p -ne $p2) { Fail 'Hai lần nhập không khớp.' }
    }
    return $p
}

function Derive-Keys([string]$pass, [byte[]]$salt, [int]$iter) {
    $kdf = [Security.Cryptography.Rfc2898DeriveBytes]::new($pass, $salt, $iter, [Security.Cryptography.HashAlgorithmName]::SHA256)
    $k = $kdf.GetBytes(64); $kdf.Dispose()
    return , $k
}

function Export-Keystore {
    $p = Read-KeystoreProps
    if (-not $p) { Fail 'Máy này chưa có keystore.properties nên không có gì để xuất.' }
    $jksPath = $p['storeFile'] -replace '/', '\'
    if (-not (Test-Path $jksPath)) { Fail "Không thấy keystore: $jksPath" }
    Ui-Section 'Xuất keystore thành file mã hoá'
    Ui-Kv 'Keystore' $jksPath
    Ui-Kv 'Alias' $p['keyAlias']
    Ui-Info 'Đặt một passphrase (≥ 10 ký tự) — CHỈ nó mở được file. Ghi nhớ nó, không gửi chung với file.'
    $pass = Get-Passphrase 'Passphrase cho file xuất' -Confirm
    $payload = [ordered]@{
        format = 'txtelex-keystore'; version = 1; created = (Get-Date).ToString('o'); from = $env:COMPUTERNAME
        fileName = Split-Path $jksPath -Leaf; keyAlias = $p['keyAlias']
        storePassword = $p['storePassword']; keyPassword = $p['keyPassword']
        sha256 = (Get-FileHash $jksPath -Algorithm SHA256).Hash
        jks = [Convert]::ToBase64String([IO.File]::ReadAllBytes($jksPath))
    } | ConvertTo-Json -Compress
    $plain = [Text.Encoding]::UTF8.GetBytes($payload)
    $iter = 300000
    $salt = New-Object byte[] 16; $iv = New-Object byte[] 16
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create(); $rng.GetBytes($salt); $rng.GetBytes($iv); $rng.Dispose()
    $keys = Derive-Keys $pass $salt $iter
    $aes = [Security.Cryptography.Aes]::Create(); $aes.KeySize = 256; $aes.Mode = 'CBC'; $aes.Padding = 'PKCS7'
    $aes.Key = $keys[0..31]; $aes.IV = $iv
    $ct = $aes.CreateEncryptor().TransformFinalBlock($plain, 0, $plain.Length); $aes.Dispose()
    $head = $KS_MAGIC + [BitConverter]::GetBytes([int]$iter) + $salt + $iv
    $hmac = [Security.Cryptography.HMACSHA256]::new([byte[]]$keys[32..63])
    $mac = $hmac.ComputeHash([byte[]]($head + $ct)); $hmac.Dispose()
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    $out = Join-Path $dist ("txtelex-keystore-{0}.txaks" -f (Get-Date -Format 'yyyyMMdd'))
    [IO.File]::WriteAllBytes($out, [byte[]]($head + $ct + $mac))
    Ui-Box 'ĐÃ XUẤT KEYSTORE' @(
        "File: $out",
        "Cỡ: $('{0:N1}' -f ((Get-Item $out).Length / 1KB)) KB · mã hoá AES-256 + HMAC-SHA256 · PBKDF2 300 000 vòng",
        '',
        'Mang sang máy thật bằng MỘT trong các cách:',
        '  • Remote Desktop: copy file trong Explorer của VPS → paste ra máy mình',
        '  • Upload lên Google Drive / OneDrive / Telegram (Saved Messages) rồi tải về',
        '  • USB / ổ cứng ngoài',
        '',
        'Trên máy thật (trong thư mục repo):',
        "  .\build.cmd keystore-import -Path <đường dẫn>\$(Split-Path $out -Leaf)",
        '',
        'File chứa CẢ khoá ký lẫn mật khẩu — chỉ passphrase mở được. Không gửi passphrase cùng chỗ với file.'
    ) 'ok'
}

function Import-Keystore {
    $file = $Path
    if (-not $file) {
        $cand = Get-ChildItem @($dist, (Join-Path $HOME 'Downloads'), (Join-Path $HOME 'Desktop')) -Filter '*.txaks' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($cand -and -not [Console]::IsInputRedirected) {
            $ans = Read-Host "  Dùng file $($cand.FullName)? [Y/n]"
            if ($ans -eq '' -or $ans -match '^[yYcC]') { $file = $cand.FullName }
        }
        if (-not $file) { Fail 'Chưa chỉ file .txaks.' @('.\build.cmd keystore-import -Path D:\txtelex-keystore-xxxx.txaks') }
    }
    if (-not (Test-Path $file)) { Fail "Không thấy file: $file" }
    Ui-Section 'Nhập keystore'
    Ui-Kv 'File' $file
    $bytes = [IO.File]::ReadAllBytes($file)
    $m = $KS_MAGIC.Length
    if ($bytes.Length -lt $m + 4 + 32 + 32 -or [Text.Encoding]::ASCII.GetString($bytes, 0, $m) -ne "TXAKS1`n") { Fail 'Không phải file keystore TxTelex (.txaks) hoặc file bị hỏng.' }
    $iter = [BitConverter]::ToInt32($bytes, $m)
    $salt = [byte[]]$bytes[($m + 4)..($m + 19)]; $iv = [byte[]]$bytes[($m + 20)..($m + 35)]
    $head = [byte[]]$bytes[0..($m + 35)]
    $ct = [byte[]]$bytes[($m + 36)..($bytes.Length - 33)]
    $mac = [byte[]]$bytes[($bytes.Length - 32)..($bytes.Length - 1)]
    $pass = Get-Passphrase 'Passphrase của file'
    $keys = Derive-Keys $pass $salt $iter
    $hmac = [Security.Cryptography.HMACSHA256]::new([byte[]]$keys[32..63])
    $calc = $hmac.ComputeHash([byte[]]($head + $ct)); $hmac.Dispose()
    $diff = 0; for ($i = 0; $i -lt 32; $i++) { $diff = $diff -bor ($calc[$i] -bxor $mac[$i]) }
    if ($diff -ne 0) { Fail 'Sai passphrase (hoặc file đã bị sửa / tải về không trọn vẹn).' @('Nhập lại đúng passphrase đã đặt lúc  keystore-export.', 'Nếu chắc chắn đúng: tải / chép lại file rồi thử lần nữa.') }
    $aes = [Security.Cryptography.Aes]::Create(); $aes.KeySize = 256; $aes.Mode = 'CBC'; $aes.Padding = 'PKCS7'
    $aes.Key = $keys[0..31]; $aes.IV = $iv
    $plain = $aes.CreateDecryptor().TransformFinalBlock($ct, 0, $ct.Length); $aes.Dispose()
    $j = [Text.Encoding]::UTF8.GetString($plain) | ConvertFrom-Json
    if ($j.format -ne 'txtelex-keystore') { Fail 'Nội dung giải mã không phải keystore TxTelex.' }
    Ui-Ok "Giải mã OK · xuất từ $($j.from) lúc $($j.created)"

    $ksDir = Join-Path $HOME 'keystores'
    New-Item -ItemType Directory -Force -Path $ksDir | Out-Null
    $dest = Join-Path $ksDir $j.fileName
    $jksBytes = [Convert]::FromBase64String($j.jks)
    if (Test-Path $dest) {
        $old = (Get-FileHash $dest -Algorithm SHA256).Hash
        if ($old -ne $j.sha256) {
            $bak = "$dest.bak-$(Get-Date -Format 'yyyyMMddHHmmss')"
            Move-Item $dest $bak; Ui-Warn "Đã có keystore KHÁC ở đó — giữ bản cũ thành $(Split-Path $bak -Leaf)"
        }
    }
    [IO.File]::WriteAllBytes($dest, $jksBytes)
    if ((Get-FileHash $dest -Algorithm SHA256).Hash -ne $j.sha256) { Fail 'Keystore ghi ra không khớp checksum.' }
    Ui-Ok "Keystore → $dest"

    if ($keytool -and (Test-Path $keytool)) {
        $env:TXA_KS_TMP = $j.storePassword
        $lst = & $keytool -list -keystore $dest -storepass:env TXA_KS_TMP -alias $j.keyAlias 2>&1 | Out-String
        Remove-Item Env:TXA_KS_TMP
        if ($lst -notmatch 'PrivateKeyEntry') { Fail 'Mở keystore bằng mật khẩu trong file không được.' @('File xuất có thể đã cũ — xuất lại trên máy gốc.') }
        Ui-Ok "Kiểm tra khoá '$($j.keyAlias)' bằng keytool: OK"
    }
    $props = @(
        '# TxTelex release signing - DO NOT commit (in .gitignore). Written by: build.cmd keystore-import',
        "storeFile=$($dest -replace '\\', '/')",
        "storePassword=$($j.storePassword)",
        "keyAlias=$($j.keyAlias)",
        "keyPassword=$($j.keyPassword)"
    )
    # Java đọc .properties dạng ISO-8859-1 ⇒ ghi không BOM.
    [IO.File]::WriteAllLines((Join-Path $root 'keystore.properties'), [string[]]$props, [Text.UTF8Encoding]::new($false))
    Ui-Ok 'keystore.properties đã tạo'
    cmdkey "/generic:$CRED_TARGET" /user:$($j.keyAlias) "/pass:$($j.storePassword)" | Out-Null
    Ui-Ok "Mật khẩu cất vào Windows Credential Manager ($CRED_TARGET)"
    Ui-Box 'SẴN SÀNG KÝ RELEASE' @('Chạy  .\build.cmd release  hoặc  .\build.cmd aab', 'Có thể xoá file .txaks sau khi đã sao lưu ở nơi an toàn.') 'ok'
}

# ── Doctor ─────────────────────────────────────────────────────────────────
function Run-Doctor {
    Ui-Section 'Kiểm tra môi trường'
    $rows = @()
    function Row($name, $ok, $detail, [switch]$Optional) {
        $mark = if ($ok) { '✔' } elseif ($Optional) { '▲' } else { '✖' }
        $script:rows += [pscustomobject]@{ ' ' = $mark; 'Hạng mục' = $name; 'Chi tiết' = $detail }
    }
    $script:rows = @()
    Row 'JDK 17' ([bool]$jdk) ($(if ($jdk) { $jdk } else { 'chưa có — .\setup.cmd' }))
    Row 'local.properties' (Test-Path 'local.properties') ($(if (Test-Path 'local.properties') { "sdk.dir=$sdkDir" } else { 'chưa có — .\setup.cmd' }))
    foreach ($pk in 'platform-tools', 'platforms\android-35', 'build-tools\35.0.0', 'ndk\27.2.12479018', 'cmake\3.22.1') {
        $okp = Test-Path (Join-Path $sdkDir $pk)
        Row ($pk -replace '\\', ';') $okp ($(if ($okp) { 'có' } else { 'thiếu — .\setup.cmd' }))
    }
    $kp = Read-KeystoreProps
    if ($kp) {
        $f = $kp['storeFile'] -replace '/', '\'
        $exists = Test-Path $f
        $open = $false
        if ($exists -and $keytool) {
            $env:TXA_KS_TMP = $kp['storePassword']
            $open = (& $keytool -list -keystore $f -storepass:env TXA_KS_TMP 2>&1 | Out-String) -match 'PrivateKeyEntry'
            Remove-Item Env:TXA_KS_TMP
        }
        Row 'Keystore release' ($exists -and $open) ($(if (-not $exists) { "không thấy $f" } elseif (-not $open) { 'sai mật khẩu' } else { $f }))
    } else { Row 'Keystore release' $false 'chưa có (chỉ cần cho release/aab) — keystore-import' -Optional }
    $stored = Read-StoredPassword $CRED_TARGET
    Row 'Mật khẩu trong Credential Manager' ([bool]$stored) ($(if ($stored) { $CRED_TARGET } else { 'chưa cất' })) -Optional
    $devs = if (Test-Path $adb) { @(& $adb devices 2>&1 | Select-Object -Skip 1 | Where-Object { "$_" -match '\tdevice$' }) } else { @() }
    Row 'Điện thoại (adb)' ($devs.Count -gt 0) ("$($devs.Count) máy") -Optional
    $free = [Math]::Round((Get-PSDrive ($root.Substring(0, 1))).Free / 1GB, 1)
    Row 'Ổ đĩa trống' ($free -ge 5) "$free GB (nên ≥ 5 GB)" -Optional
    Ui-Table $script:rows @(' ', 'Hạng mục', 'Chi tiết')
    if ($script:rows | Where-Object { $_.' ' -eq '✖' }) { Write-Host ''; Ui-Warn 'Còn thiếu — chạy  .\setup.cmd  để tự cài.' } else { Write-Host ''; Ui-Ok 'Sẵn sàng build.' }
}

# ── Menu ───────────────────────────────────────────────────────────────────
function Show-Menu {
    $items = @(
        @('1', 'debug', 'APK debug (cài thử)'), @('2', 'release', 'APK release đã ký'), @('3', 'aab', 'AAB nộp Google Play'),
        @('4', 'all', 'Test + debug + release + aab'), @('5', 'test', 'Chạy unit test'), @('6', 'run', 'Build, cài lên máy, mở app'),
        @('7', 'doctor', 'Kiểm tra môi trường'), @('8', 'keystore-export', 'Xuất keystore (mang sang máy khác)'),
        @('9', 'keystore-import', 'Nhập keystore từ file .txaks'), @('0', 'clean', 'Dọn build')
    )
    Ui-Section 'Chọn việc cần làm'
    foreach ($i in $items) { Write-Host ('   [' + $i[0] + '] ') -ForegroundColor Cyan -NoNewline; Write-Host $i[2] }
    Write-Host '   [q] Thoát' -ForegroundColor DarkGray
    $c = Read-Host "`n  Chọn"
    $hit = $items | Where-Object { $_[0] -eq $c.Trim() } | Select-Object -First 1
    if (-not $hit) { return $null }
    return $hit[1]
}

# ── Chạy ───────────────────────────────────────────────────────────────────
Ui-Banner "BUILD  ·  v$version ($versionCode)" "TXA STUDIO  ·  $PKG  ·  $(Get-Date -Format 'HH:mm dd/MM/yyyy')"
if ($Target -in '', 'menu') {
    if ([Console]::IsInputRedirected) { $Target = 'debug' } else { $Target = Show-Menu; if (-not $Target) { return } }
}
$sw = [Diagnostics.Stopwatch]::StartNew()
$exit = 0
try {
    switch ($Target) {
        'help'    { Get-Help $PSCommandPath -Detailed }
        'doctor'  { Run-Doctor }
        'clean'   { Require-Env; Run-Gradle @('clean') 'Dọn build'; if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }; Ui-Ok 'Đã dọn build\ và dist\' }
        'test'    { Require-Env; Run-Gradle @(':app:testDebugUnitTest') 'Unit test'; Ui-Ok 'Toàn bộ test qua' }
        'debug'   { Require-Env; Run-Gradle @('assembleDebug') 'APK debug'; Collect 'debug' | Out-Null }
        'release' { Require-Env; Test-Signing | Out-Null; Run-Gradle @('assembleRelease') 'APK release'; Collect 'release' | Out-Null }
        'aab'     { Require-Env; Test-Signing | Out-Null; Run-Gradle @('bundleRelease') 'AAB Google Play'; Collect 'aab' | Out-Null }
        'all'     {
            Require-Env; Test-Signing | Out-Null
            Run-Gradle @(':app:testDebugUnitTest', 'assembleDebug', 'assembleRelease', 'bundleRelease') 'Test + build tất cả'
            Collect 'debug' | Out-Null; Collect 'release' | Out-Null; Collect 'aab' | Out-Null
        }
        'install' {
            Require-Env
            if ($Variant -eq 'release') { if (-not (Test-Signing)) { Fail 'Bản release chưa ký thì không cài được.' @('.\build.cmd keystore-import -Path <file .txaks>') }; Run-Gradle @('assembleRelease') 'APK release'; Install-Apk (Collect 'release') | Out-Null }
            else { Run-Gradle @('assembleDebug') 'APK debug'; Install-Apk (Collect 'debug') | Out-Null }
        }
        'run'     {
            Require-Env; Run-Gradle @('assembleDebug') 'APK debug'
            $id = Install-Apk (Collect 'debug')
            & $adb -s $id shell am start -n "$PKG/com.customkey.vietime.ui.MainActivity" 2>&1 | Out-Null
            Ui-Ok 'Đã mở TxTelex trên máy'
        }
        'keystore-export' { Export-Keystore }
        'keystore-import' { Import-Keystore }
        'key-password' {
            $pw = Read-StoredPassword $CRED_TARGET
            if ($pw) { Ui-Kv 'Mật khẩu' $pw 'Yellow'; Ui-Kv 'Alias' 'txtelex' }
            else { Ui-Warn 'Chưa có trong Credential Manager.'; Ui-Info "Thêm:  cmdkey /generic:$CRED_TARGET /user:txtelex /pass:<mật khẩu>   ·  hoặc  .\build.cmd keystore-import" }
            $kp = Read-KeystoreProps; if ($kp) { Ui-Kv 'Keystore' ($kp['storeFile'] -replace '/', '\') }
        }
    }
} catch [TxaFail] {
    $exit = 1
    if ($_.Exception.Message -ne '__shown__') {
        $lines = @($_.Exception.Message)
        if ($_.Exception.Hints) { $lines += ''; $lines += ($_.Exception.Hints | ForEach-Object { "→ $_" }) }
        Ui-Box 'LỖI' $lines 'error'
    }
} catch {
    $exit = 1
    Ui-Box 'LỖI KHÔNG LƯỜNG TRƯỚC' @("$($_.Exception.Message)", '', "Tại: $($_.InvocationInfo.PositionMessage -split "`n" | Select-Object -First 1)") 'error'
}
Show-Artifacts
Write-Host ''
if ($exit -eq 0) { Ui-Ok ("Xong · $Target · " + (Ui-Duration $sw.Elapsed)) } else { Ui-Err ("Thất bại · $Target · " + (Ui-Duration $sw.Elapsed)) }
exit $exit
