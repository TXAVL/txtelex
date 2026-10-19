<#
.SYNOPSIS
  Cài MỌI công cụ cần để build TxTelex trên một máy Windows mới — chạy một lần.

.DESCRIPTION
  Tự phát hiện thứ đã có, chỉ cài phần thiếu (chạy lại bao nhiêu lần cũng được):
    1. JDK 17                      (winget: Microsoft.OpenJDK.17)
    2. Android command-line tools  (dl.google.com)
    3. Chấp nhận giấy phép Android SDK
    4. platform-tools · platforms;android-35 · build-tools;35.0.0 · ndk;27.2.12479018 · cmake;3.22.1
    5. local.properties (sdk.dir) + biến môi trường JAVA_HOME / ANDROID_HOME cho user
  Cần Internet. Lần đầu tải khoảng 2–3 GB (SDK + NDK + Gradle + thư viện).

.EXAMPLE
  .\setup.cmd                        # cài vào C:\Android
  .\setup.cmd -SdkRoot D:\Android    # chọn thư mục SDK khác
  .\setup.cmd -Build                 # cài xong build thử APK debug luôn
#>
param(
    [string]$SdkRoot = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'C:\Android' }),
    [switch]$Build
)

# Continue: lệnh native (java, sdkmanager) in ra stderr là chuyện thường — PS 5.1 coi là lỗi nếu Stop.
# Bước nào hỏng thì script tự throw; cmdlet quan trọng gắn -ErrorAction Stop.
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest nhanh hơn nhiều khi tắt thanh tiến trình
$root = $PSScriptRoot

# Phải khớp app/build.gradle.kts
$CMDLINE_TOOLS_URL = 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip'
$SDK_PACKAGES = @('platform-tools', 'platforms;android-35', 'build-tools;35.0.0', 'ndk;27.2.12479018', 'cmake;3.22.1')

function Step($n, $msg) { Write-Host "`n[$n/5] $msg" -ForegroundColor Cyan }
function Ok($msg) { Write-Host "  ✔ $msg" -ForegroundColor Green }
function Info($msg) { Write-Host "  · $msg" -ForegroundColor Gray }

# PATH một số máy có mục lỗi chứa dấu " (vd: C:\Program Files\dotnet") làm Java/Gradle hỏng.
$env:PATH = ($env:PATH -split ';' | Where-Object { $_ -and $_ -notmatch '"' }) -join ';'

Write-Host 'TxTelex — cài môi trường build' -ForegroundColor Magenta
Write-Host "SDK: $SdkRoot"

# ── 1. JDK 17 ───────────────────────────────────────────────────────────────
Step 1 'JDK 17'
function Find-Jdk17 {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    foreach ($base in 'C:\Program Files\Microsoft', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java', 'C:\Program Files\Zulu', 'C:\Program Files\Amazon Corretto') {
        if (Test-Path $base) { $candidates += (Get-ChildItem $base -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '(jdk|zulu).*17|^17|jdk-17' } | ForEach-Object FullName) }
    }
    foreach ($c in $candidates) {
        $java = Join-Path $c 'bin\java.exe'
        if (Test-Path $java) {
            $v = (cmd /c "`"$java`" -version 2>&1" | Select-Object -First 1) -as [string]
            if ($v -match '"17\.') { return $c }
        }
    }
    return $null
}
$jdk = Find-Jdk17
if (-not $jdk) {
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        throw 'Không có winget. Tải JDK 17 thủ công: https://learn.microsoft.com/java/openjdk/download#openjdk-17 rồi chạy lại.'
    }
    Info 'Đang cài Microsoft OpenJDK 17 (winget, có thể hỏi quyền Admin)…'
    winget install -e --id Microsoft.OpenJDK.17 --accept-source-agreements --accept-package-agreements --silent | Out-Null
    $jdk = Find-Jdk17
    if (-not $jdk) { throw 'Cài JDK xong nhưng không tìm thấy — mở lại cửa sổ PowerShell rồi chạy lại setup.' }
}
$env:JAVA_HOME = $jdk
[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'User')
Ok "JDK 17: $jdk"

# ── 2. Android command-line tools ──────────────────────────────────────────
Step 2 'Android command-line tools'
$sdkmanager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkmanager)) {
    New-Item -ItemType Directory -Force -Path (Join-Path $SdkRoot 'cmdline-tools') | Out-Null
    $zip = Join-Path $env:TEMP 'txtelex-cmdline-tools.zip'
    Info "Tải $CMDLINE_TOOLS_URL"
    Invoke-WebRequest -Uri $CMDLINE_TOOLS_URL -OutFile $zip -UseBasicParsing -ErrorAction Stop
    $tmp = Join-Path $env:TEMP 'txtelex-cmdline-tools'
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive -Path $zip -DestinationPath $tmp -Force -ErrorAction Stop
    $dest = Join-Path $SdkRoot 'cmdline-tools\latest'
    if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
    Move-Item (Join-Path $tmp 'cmdline-tools') $dest -ErrorAction Stop
    Remove-Item $zip, $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
$env:ANDROID_HOME = $SdkRoot
[Environment]::SetEnvironmentVariable('ANDROID_HOME', $SdkRoot, 'User')
Ok "sdkmanager: $sdkmanager"

# ── 3. Giấy phép SDK ────────────────────────────────────────────────────────
Step 3 'Chấp nhận giấy phép Android SDK'
$yes = Join-Path $env:TEMP 'txtelex-yes.txt'
Set-Content -Path $yes -Value (@('y') * 60) -Encoding ascii
# cmd bóc cặp ngoặc kép ngoài cùng ⇒ bọc thêm một lớp để giữ nguyên đường dẫn có dấu cách.
$licenseCmd = "`"$sdkmanager`" --sdk_root=`"$SdkRoot`" --licenses < `"$yes`""
cmd /c "`"$licenseCmd`"" 2>&1 | Out-Null
Remove-Item $yes -ErrorAction SilentlyContinue
Ok 'Đã chấp nhận'

# ── 4. Gói SDK ──────────────────────────────────────────────────────────────
Step 4 'Gói SDK / NDK / CMake'
$missing = $SDK_PACKAGES | Where-Object {
    $dir = Join-Path $SdkRoot ($_ -replace ';', '\')
    -not (Test-Path $dir)
}
if ($missing) {
    Info "Cài: $($missing -join ', ')  (NDK khoảng 1 GB, chờ chút…)"
    & $sdkmanager "--sdk_root=$SdkRoot" @missing 2>&1 | Where-Object { $_ -notmatch '^\[=*' } | Select-Object -Last 3 | ForEach-Object { Info $_ }
    if ($LASTEXITCODE -ne 0) { throw 'sdkmanager lỗi — kiểm tra mạng / proxy rồi chạy lại.' }
}
foreach ($p in $SDK_PACKAGES) {
    if (Test-Path (Join-Path $SdkRoot ($p -replace ';', '\'))) { Ok $p } else { throw "Thiếu $p sau khi cài" }
}

# ── 5. local.properties ─────────────────────────────────────────────────────
Step 5 'local.properties'
$escaped = $SdkRoot -replace '\\', '\\' -replace ':', '\:'
Set-Content -Path (Join-Path $root 'local.properties') -Value "sdk.dir=$escaped" -Encoding ascii
Ok "sdk.dir=$SdkRoot"
if (-not (Test-Path (Join-Path $root 'keystore.properties'))) {
    Info 'Chưa có keystore.properties ⇒ build debug bình thường; bản release sẽ không ký (xem README › Ký bản release).'
}

Write-Host "`n✔ Môi trường sẵn sàng." -ForegroundColor Green
Write-Host '  Build:  .\build.cmd        (APK debug)'
Write-Host '          .\build.cmd all    (test + debug + release + aab)'
if ($Build) {
    & (Join-Path $root 'build.ps1') debug
}
