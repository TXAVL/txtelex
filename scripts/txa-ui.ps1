# txa-ui.ps1 — giao diện console + chẩn đoán lỗi dùng chung cho build.ps1 / setup.ps1.
# Tương thích Windows PowerShell 5.1 và PowerShell 7. Dot-source:  . "$PSScriptRoot\scripts\txa-ui.ps1"

try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
$script:TxaFancy = -not [Console]::IsOutputRedirected
$script:TxaWidth = 78
try { if ($script:TxaFancy) { $script:TxaWidth = [Math]::Max(60, [Math]::Min(100, [Console]::WindowWidth - 2)) } } catch {}

function Ui-Line([string]$ch = '─', [ConsoleColor]$c = 'DarkGray') { Write-Host ($ch * $script:TxaWidth) -ForegroundColor $c }

function Ui-Banner([string]$title, [string]$subtitle) {
    $w = $script:TxaWidth
    Write-Host ''
    Write-Host ('  ▀█▀ ▀▄▀ ▀█▀ █▀▀ █   █▀▀ ▀▄▀') -ForegroundColor Cyan
    Write-Host ('   █  █ █  █  ██▄ █▄▄ ██▄ █ █   ') -ForegroundColor Cyan -NoNewline
    Write-Host $title -ForegroundColor White
    if ($subtitle) { Write-Host ('  ' + $subtitle) -ForegroundColor DarkGray }
    Write-Host ('  ' + ('━' * ($w - 2))) -ForegroundColor DarkCyan
}

function Ui-Section([string]$t) { Write-Host ''; Write-Host "  ◆ $t" -ForegroundColor Magenta }
function Ui-Ok([string]$t) { Write-Host '  ✔ ' -ForegroundColor Green -NoNewline; Write-Host $t }
function Ui-Info([string]$t) { Write-Host "  · $t" -ForegroundColor Gray }
function Ui-Warn([string]$t) { Write-Host '  ▲ ' -ForegroundColor Yellow -NoNewline; Write-Host $t -ForegroundColor Yellow }
function Ui-Err([string]$t) { Write-Host '  ✖ ' -ForegroundColor Red -NoNewline; Write-Host $t -ForegroundColor Red }
function Ui-Kv([string]$k, [string]$v, [ConsoleColor]$c = 'White') {
    Write-Host ('  ' + $k.PadRight(16)) -ForegroundColor DarkGray -NoNewline; Write-Host $v -ForegroundColor $c
}

function Ui-Duration([TimeSpan]$t) {
    if ($t.TotalMinutes -ge 1) { return '{0}m {1:00}s' -f [int][Math]::Floor($t.TotalMinutes), $t.Seconds }
    return '{0:N1}s' -f $t.TotalSeconds
}

# Hộp nhiều dòng — [kind]: ok | warn | error | info
function Ui-Box([string]$title, [string[]]$lines, [string]$kind = 'info') {
    $c = switch ($kind) { 'ok' { 'Green' } 'warn' { 'Yellow' } 'error' { 'Red' } default { 'Cyan' } }
    $w = $script:TxaWidth - 4
    Write-Host ''
    Write-Host ('  ╭─ ' + $title + ' ' + ('─' * [Math]::Max(1, $w - $title.Length - 3)) + '╮') -ForegroundColor $c
    foreach ($raw in $lines) {
        foreach ($l in (Ui-Wrap $raw ($w - 2))) {
            Write-Host '  │ ' -ForegroundColor $c -NoNewline
            Write-Host ($l.PadRight($w - 2)) -NoNewline
            Write-Host ' │' -ForegroundColor $c
        }
    }
    Write-Host ('  ╰' + ('─' * $w) + '╯') -ForegroundColor $c
}

# Ngắt dòng theo từ; dòng tràn thụt vào thêm 2 so với dòng gốc.
function Ui-Wrap([string]$s, [int]$n) {
    if ($s.Length -le $n) { return , $s }
    $indent = ' ' * ([regex]::Match($s, '^\s*').Length + 2)
    $out = @(); $rest = $s; $first = $true
    while ($rest.Length -gt $n) {
        $cut = $rest.LastIndexOf(' ', $n); if ($cut -lt $n / 2) { $cut = $n }
        $out += $rest.Substring(0, $cut); $rest = $indent + $rest.Substring($cut).TrimStart()
        if ($first) { $first = $false; $n = [Math]::Max($n, $indent.Length + 10) }
    }
    if ($rest.Trim()) { $out += $rest }
    return $out
}

# Bảng: $rows = mảng hashtable/pscustomobject, $cols = tên cột theo thứ tự
function Ui-Table($rows, [string[]]$cols) {
    if (-not $rows) { return }
    $widths = @{}
    foreach ($c in $cols) { $widths[$c] = [Math]::Max($c.Length, ($rows | ForEach-Object { "$($_.$c)".Length } | Measure-Object -Maximum).Maximum) }
    Write-Host ('  ' + (($cols | ForEach-Object { $_.PadRight($widths[$_]) }) -join '   ')) -ForegroundColor DarkCyan
    Write-Host ('  ' + (($cols | ForEach-Object { '─' * $widths[$_] }) -join '   ')) -ForegroundColor DarkGray
    foreach ($r in $rows) {
        $i = 0
        Write-Host '  ' -NoNewline
        foreach ($c in $cols) {
            $v = "$($r.$c)".PadRight($widths[$c])
            $color = if ($v -match '^\s*✔') { 'Green' } elseif ($v -match '^\s*▲|chưa ký') { 'Yellow' } elseif ($i -eq 0) { 'White' } else { 'Gray' }
            Write-Host $v -ForegroundColor $color -NoNewline
            if ($i -lt $cols.Count - 1) { Write-Host '   ' -NoNewline }
            $i++
        }
        Write-Host ''
    }
}

# ── Chạy Gradle với dòng trạng thái động + log đầy đủ ra file ──────────────
$script:TxaSpin = '⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏'

# "…/app/src/main/java/com/customkey/vietime/ime/X.kt" → "ime\X.kt"
function Short-TxaPath([string]$p) {
    $p = $p -replace '/', '\'
    foreach ($cut in '\com\customkey\vietime\', '\src\') {
        $i = $p.LastIndexOf($cut); if ($i -ge 0) { return $p.Substring($i + $cut.Length) }
    }
    return $p
}

# Dòng lỗi Kotlin trong lúc chạy: "e: file:///…/X.kt:3:18 msg" → "X.kt:3  msg"
function Format-TxaLine([string]$line) {
    if ($line -match '^[ew]: file:///(.+?):(\d+):\d+ (.+)$') { return "$(Short-TxaPath $Matches[1]):$($Matches[2])  $($Matches[3])" }
    return $line
}

function Invoke-TxaGradle([string[]]$tasks, [string]$logFile) {
    $start = Get-Date
    $taskCount = 0; $spin = 0; $status = 'khởi động Gradle…'
    $buildOk = $false
    New-Item -ItemType Directory -Force -Path (Split-Path $logFile) | Out-Null
    "# gradlew $($tasks -join ' ')  ·  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Set-Content -Path $logFile -Encoding UTF8
    $sw = [IO.StreamWriter]::new($logFile, $true, [Text.UTF8Encoding]::new($false))
    try {
        & .\gradlew.bat @tasks --console=plain 2>&1 | ForEach-Object {
            # PS 5.1 gói dòng stderr thành ErrorRecord; dòng rỗng thì ToString() ra "RemoteException".
            $line = if ($_ -is [Management.Automation.ErrorRecord]) { "$($_.TargetObject)" } else { "$_" }
            $sw.WriteLine($line)
            if ($line -match '^> Task (\S+)') { $taskCount++; $status = $Matches[1] }
            elseif ($line -match '^BUILD SUCCESSFUL') { $buildOk = $true }
            $important = $line -match '^e: |^\S.* FAILED$'
            if ($script:TxaFancy) {
                $el = Ui-Duration ((Get-Date) - $start)
                $frame = $script:TxaSpin[$spin++ % $script:TxaSpin.Length]
                $msg = "  $frame  $el  ·  $taskCount bước  ·  $status"
                if ($msg.Length -gt $script:TxaWidth) { $msg = $msg.Substring(0, $script:TxaWidth - 1) + '…' }
                if ($important) { Write-Host ("`r" + (' ' * $script:TxaWidth) + "`r") -NoNewline; Write-Host "  ✖ $(Format-TxaLine $line)" -ForegroundColor Red }
                Write-Host ("`r" + $msg.PadRight($script:TxaWidth)) -NoNewline -ForegroundColor DarkCyan
            } elseif ($important) {
                Write-Host "  ✖ $(Format-TxaLine $line)"
            }
        }
    } finally { $sw.Dispose() }
    $code = $LASTEXITCODE
    if ($script:TxaFancy) { Write-Host ("`r" + (' ' * $script:TxaWidth) + "`r") -NoNewline }
    $el = Ui-Duration ((Get-Date) - $start)
    if ($code -eq 0 -and $buildOk) { Ui-Ok "Gradle xong · $taskCount bước · $el" }
    return [pscustomobject]@{ Ok = ($code -eq 0); Code = $code; Log = $logFile; Elapsed = $el }
}

# ── Chẩn đoán lỗi từ log ───────────────────────────────────────────────────
# Thông báo assert của test hỏng, lấy từ JUnit XML (log Gradle không in ra).
function Get-TxaTestFailure([string]$root, [string]$cls, [string]$name) {
    $dir = Join-Path $root 'app\build\test-results\testDebugUnitTest'
    $f = Get-ChildItem $dir -Filter "TEST-*.$cls.xml" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $f) { return $null }
    try {
        [xml]$x = Get-Content $f.FullName -Raw -Encoding UTF8
        $tc = $x.testsuite.testcase | Where-Object { $_.name -eq $name } | Select-Object -First 1
        $m = "$($tc.failure.message)".Trim() -split "`n" | Select-Object -First 1
        if ($m.Length -gt 160) { $m = $m.Substring(0, 157) + '…' }
        return $m
    } catch { return $null }
}

function Get-TxaDiagnosis([string]$logFile, [string]$root) {
    $log = if (Test-Path $logFile) { Get-Content $logFile -Encoding UTF8 } else { @() }
    $text = $log -join "`n"
    $d = [ordered]@{ Cause = $null; Fix = @(); Details = @() }

    $kotlin = @($log | Where-Object { $_ -match '^e: file:///(.+?):(\d+):(\d+) (.+)$' } | Select-Object -First 8 | ForEach-Object {
        $null = $_ -match '^e: file:///(.+?):(\d+):(\d+) (.+)$'
        "▸ $(Short-TxaPath $Matches[1]):$($Matches[2]):$($Matches[3])"
        "    $($Matches[4])"
    })
    $failedTests = @()
    for ($i = 0; $i -lt $log.Count -and $failedTests.Count -lt 24; $i++) {
        if ($log[$i] -notmatch '^(\S+) > (.+) FAILED$') { continue }
        $cls = $Matches[1]; $name = $Matches[2]
        $at = if ($i + 1 -lt $log.Count -and $log[$i + 1] -match '^\s+(\S+) at (\S+)$') { "$($Matches[1] -replace '^.*\.', '') @ $($Matches[2])" }
        $failedTests += "▸ $cls › $name"
        $msg = Get-TxaTestFailure $root $cls $name
        if ($msg) { $failedTests += "    $msg" }
        if ($at) { $failedTests += "    $at" }
    }

    if ($text -match 'Could not find or load main class Files') {
        $d.Cause = 'Biến PATH của Windows có mục chứa dấu " làm Java khởi động sai.'
        $d.Fix = @('Dùng .\build.cmd (tự lọc PATH) thay vì gọi gradlew trực tiếp,', 'hoặc sửa PATH trong System Properties › Environment Variables (xoá dấu " thừa).')
    } elseif ($text -match 'SDK location not found|sdk\.dir') {
        $d.Cause = 'Chưa trỏ project tới Android SDK (thiếu local.properties).'
        $d.Fix = @('Chạy  .\setup.cmd  một lần.')
    } elseif ($text -match "NDK (is )?not configured|No version of NDK matched|NDK at .* did not have|CMake '3\.22\.1' was not found|Failed to find CMake") {
        $d.Cause = 'Thiếu NDK 27.2.12479018 hoặc CMake 3.22.1 (cần cho bộ bắt crash native).'
        $d.Fix = @('Chạy lại  .\setup.cmd  (tự cài phần thiếu).')
    } elseif ($text -match 'requires Java 17|Unsupported class file major version|Android Gradle plugin requires Java|JAVA_HOME is set to an invalid') {
        $d.Cause = 'Sai phiên bản Java — cần đúng JDK 17.'
        $d.Fix = @('Chạy  .\setup.cmd  hoặc cài: winget install -e --id Microsoft.OpenJDK.17', 'rồi mở lại cửa sổ PowerShell.')
    } elseif ($text -match 'Keystore was tampered with|password was incorrect|Given final block not properly padded|Failed to read key .* from store') {
        $d.Cause = 'Sai mật khẩu keystore / khoá trong keystore.properties.'
        $d.Fix = @('Kiểm tra storePassword / keyPassword / keyAlias trong keystore.properties.', 'Xem lại mật khẩu đã cất:  .\build.cmd key-password')
    } elseif ($text -match "Keystore file '([^']+)' not found|storeFile.*does not exist") {
        $d.Cause = 'Không thấy file keystore mà keystore.properties trỏ tới.'
        $d.Fix = @('Sửa storeFile trong keystore.properties, hoặc nhập lại:  .\build.cmd keystore-import')
    } elseif ($text -match 'OutOfMemoryError|Java heap space|GC overhead limit|Gradle build daemon disappeared') {
        $d.Cause = 'Gradle hết bộ nhớ.'
        $d.Fix = @('Đóng bớt ứng dụng, hoặc tăng org.gradle.jvmargs trong gradle.properties (vd -Xmx4g).', 'Rồi chạy:  .\gradlew.bat --stop')
    } elseif ($text -match 'Timeout waiting to lock|is currently in use by another Gradle instance') {
        $d.Cause = 'Một tiến trình Gradle khác đang giữ khoá.'
        $d.Fix = @('Chạy  .\gradlew.bat --stop  rồi build lại.')
    } elseif ($text -match 'Could not resolve|Could not GET|Could not download|UnknownHostException|Connection (timed out|refused)|PKIX path building failed|Read timed out') {
        $d.Cause = 'Không tải được thư viện / Gradle (mạng, proxy hoặc tường lửa).'
        $d.Fix = @('Kiểm tra Internet; cần truy cập dl.google.com, repo.maven.apache.org, services.gradle.org.', 'Sau đó build lại (Gradle tự tiếp tục).')
    } elseif ($kotlin) {
        $n = @($log | Where-Object { $_ -match '^e: ' }).Count
        $d.Cause = "Lỗi biên dịch Kotlin ($n chỗ$(if ($n -gt 8) { ', hiện 8 chỗ đầu' }))."
        $d.Fix = @('Sửa các dòng bên dưới rồi build lại.')
        $d.Details = $kotlin
    } elseif ($failedTests -or $text -match 'There were failing tests') {
        $n = @($log | Where-Object { $_ -match '^\S+ > .+ FAILED$' }).Count
        $d.Cause = "Unit test hỏng ($n test)."
        $d.Fix = @('Báo cáo chi tiết: app\build\reports\tests\testDebugUnitTest\index.html')
        $d.Details = $failedTests
    } else {
        $what = ($log | Select-String -Pattern "What went wrong:" -Context 0, 4 | Select-Object -First 1)
        $d.Cause = 'Gradle báo lỗi.'
        if ($what) { $d.Details = @($what.Context.PostContext | Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() }) }
        else { $d.Details = @($log | Where-Object { $_ -match 'error|lỗi|exception|failed' } | Select-Object -Last 8) }
        $d.Fix = @('Xem log đầy đủ bên dưới.')
    }
    return [pscustomobject]$d
}

function Show-TxaFailure($result, [string]$root) {
    $diag = Get-TxaDiagnosis $result.Log $root
    $lines = @("Nguyên nhân: $($diag.Cause)", '')
    foreach ($f in $diag.Fix) { $lines += "→ $f" }
    if ($diag.Details) { $lines += ''; foreach ($x in $diag.Details) { $lines += "  $x" } }
    $lines += ''; $lines += "Log đầy đủ: $($result.Log)"
    Ui-Box "BUILD LỖI (mã $($result.Code))" $lines 'error'
}
