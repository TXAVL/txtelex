# TxTelex — Vietnamese Smart Keyboard

Bàn phím tiếng Việt cho Android: gõ Telex/VNI, tự sửa chính tả, gõ vuốt, trackpad phím Cách, xoá theo từ,
Theme Studio, âm phím cơ, clipboard thông minh — chạy 100% trên máy. Package: `nm.txa.telex`.

![TxTelex](docs/assets/playstore_feature_graphic_1024x500.png)

**Tải bản mới nhất:** [Releases](https://github.com/TXAVL/txtelex/releases)

---

## ⚡ Build nhanh (Windows 10/11, không cần Android Studio)

```powershell
git clone https://github.com/TXAVL/txtelex.git
cd txtelex
.\setup.cmd          # cài JDK 17 + Android SDK + NDK + CMake (một lần, ~2–3 GB)
.\build.cmd          # → dist\txtelex-<phiên bản>-debug.apk
```

`setup.cmd` tự phát hiện thứ đã có và chỉ cài phần thiếu — chạy lại lúc nào cũng được. Tuỳ chọn:

| Lệnh | Tác dụng |
|---|---|
| `.\setup.cmd -SdkRoot D:\Android` | Đặt Android SDK ở thư mục khác (mặc định `C:\Android`) |
| `.\setup.cmd -Build` | Cài xong build thử APK debug luôn |

> Lần build đầu Gradle tự tải Gradle 8.14.3 + thư viện (vài trăm MB) — cần Internet, mất vài phút.
> Những lần sau chỉ vài giây.

### Các lệnh build

| Lệnh | Kết quả trong `dist\` |
|---|---|
| `.\build.cmd` hoặc `.\build.cmd debug` | `txtelex-<ver>-debug.apk` (ký debug, cài thử) |
| `.\build.cmd release` | `txtelex-<ver>-release.apk` (ký khoá release, R8 thu gọn) |
| `.\build.cmd aab` | `txtelex-<ver>.aab` — file nộp Google Play |
| `.\build.cmd all` | Chạy test + cả 3 file trên |
| `.\build.cmd test` | Chạy toàn bộ unit test |
| `.\build.cmd run` | Build debug, cài lên điện thoại đang cắm USB, mở app |
| `.\build.cmd install release` | Build + cài bản release lên điện thoại |
| `.\build.cmd clean` | Dọn `build\` và `dist\` |
| `.\build.cmd key-password` | Xem mật khẩu keystore đã cất trong Windows Credential Manager |

Dùng Gradle trực tiếp cũng được (sau khi đã chạy `setup.cmd`):
```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug        # app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat bundleRelease        # app\build\outputs\bundle\release\app-release.aab
```

---

## 🧰 Cài thủ công (nếu không muốn dùng `setup.cmd`)

| Công cụ | Phiên bản | Tải chính thức |
|---|---|---|
| Git | mới nhất | https://git-scm.com/downloads/win · hoặc `winget install -e --id Git.Git` |
| JDK | **17** | Microsoft: https://learn.microsoft.com/java/openjdk/download#openjdk-17 · hoặc Temurin: https://adoptium.net/temurin/releases/?version=17 · hoặc `winget install -e --id Microsoft.OpenJDK.17` |
| Android command-line tools | 11076708 | https://developer.android.com/studio#command-line-tools-only (file `commandlinetools-win-…_latest.zip`) |
| platform-tools (adb) | mới nhất | qua `sdkmanager` (bên dưới) |
| Android SDK Platform | **35** | qua `sdkmanager` |
| Build-Tools | **35.0.0** | qua `sdkmanager` |
| NDK | **27.2.12479018** | qua `sdkmanager` (bộ bắt crash native `libtxaguard.so`) |
| CMake | **3.22.1** | qua `sdkmanager` |
| winget (nếu máy chưa có) | — | cài "App Installer": https://learn.microsoft.com/windows/package-manager/winget/ |

```powershell
# 1. JDK 17
winget install -e --id Microsoft.OpenJDK.17

# 2. Android command-line tools → C:\Android\cmdline-tools\latest
New-Item -ItemType Directory -Force -Path C:\Android\cmdline-tools | Out-Null
Invoke-WebRequest https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip -OutFile $env:TEMP\clt.zip
Expand-Archive $env:TEMP\clt.zip -DestinationPath $env:TEMP\clt -Force
Move-Item $env:TEMP\clt\cmdline-tools C:\Android\cmdline-tools\latest

# 3. Biến môi trường (mở lại PowerShell sau khi chạy)
[Environment]::SetEnvironmentVariable("ANDROID_HOME", "C:\Android", "User")

# 4. Giấy phép + gói SDK
C:\Android\cmdline-tools\latest\bin\sdkmanager.bat --licenses
C:\Android\cmdline-tools\latest\bin\sdkmanager.bat "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;27.2.12479018" "cmake;3.22.1"

# 5. Trỏ project vào SDK (trong thư mục repo)
Set-Content local.properties 'sdk.dir=C\:\\Android'
```

---

## 🔐 Ký bản release

Bản **debug** không cần gì thêm. Bản **release / AAB** cần keystore — file này **không có trong repo** (cố ý).

**Máy mới, dùng lại khoá cũ** (bắt buộc nếu đã phát hành trên Google Play — đổi khoá là không cập nhật được app):
1. Chép file `txtelex-release.jks` từ nơi sao lưu sang máy mới (vd `C:\Users\<bạn>\keystores\`).
2. Chép `keystore.properties.example` thành `keystore.properties` (cùng thư mục với `build.cmd`) rồi điền đường dẫn + mật khẩu.
3. `.\build.cmd release` — dòng `✔ Chữ ký: CN=TxTelex 2027, … O=TXA STUDIO …` là đúng khoá.

**Chưa có khoá** (chỉ làm MỘT lần cho cả đời app):
```powershell
keytool -genkeypair -v -keystore $HOME\keystores\txtelex-release.jks -storetype PKCS12 `
  -alias txtelex -keyalg RSA -keysize 4096 -validity 10950 `
  -dname "CN=TxTelex 2027, OU=TxTelex Keyboard, O=TXA STUDIO, L=Ho Chi Minh City, C=VN"
```
(`keytool` nằm trong `%JAVA_HOME%\bin`.) Cất mật khẩu vào Windows Credential Manager để `build.cmd key-password` đọc lại được:
```powershell
cmdkey /generic:TxTelex/release-keystore /user:txtelex /pass:<mật khẩu>
```

> ⚠️ **Mất file `.jks` hoặc mật khẩu = không bao giờ cập nhật được app trên Google Play.** Luôn giữ ít nhất
> một bản sao ở nơi khác máy build (USB cất riêng, Google Drive / OneDrive, Bitwarden / 1Password…).
> Nên bật **Play App Signing** khi tạo app trên Play Console để có đường khôi phục.

---

## 📱 Cài lên điện thoại

1. Bật **Tuỳ chọn nhà phát triển** → **Gỡ lỗi USB**: https://developer.android.com/studio/debug/dev-options
2. Cắm cáp, chọn **Cho phép gỡ lỗi USB** trên điện thoại. Kiểm tra: `C:\Android\platform-tools\adb.exe devices`
3. `.\build.cmd run` (hoặc tải APK trong [Releases](https://github.com/TXAVL/txtelex/releases) rồi mở trên máy).
4. Mở app TxTelex → làm theo 2 bước **Kích hoạt bàn phím**.

Máy không hiện trong `adb devices`: cài driver USB — Google/Pixel: https://developer.android.com/studio/run/win-usb ·
hãng khác (Samsung, Xiaomi, Oppo…): https://developer.android.com/studio/run/oem-usb

---

## 🛠️ Xử lý lỗi thường gặp

| Lỗi | Cách sửa |
|---|---|
| `SDK location not found` | Chạy `.\setup.cmd` (tạo `local.properties`) |
| `JAVA_HOME is not set` / sai bản Java | Chạy `.\setup.cmd`, hoặc cài JDK **17** (không phải 8/11/21) rồi mở lại PowerShell |
| `NDK not configured` / `CMake '3.22.1' was not found` | `sdkmanager "ndk;27.2.12479018" "cmake;3.22.1"` hoặc chạy lại `setup.cmd` |
| `Could not find or load main class Files\…` khi chạy test | PATH Windows có mục chứa dấu `"`. `build.cmd` tự lọc; nếu gọi `gradlew` trực tiếp, sửa PATH trong *System Properties › Environment Variables* |
| `running scripts is disabled on this system` | Dùng `build.cmd` / `setup.cmd` (đã kèm `-ExecutionPolicy Bypass`) thay vì gọi `.ps1` trực tiếp |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` khi cài | Máy đang có bản ký khoá khác (debug ↔ release). Gỡ TxTelex cũ rồi cài lại |
| Bản release báo *APK chưa được ký* | Thiếu / sai `keystore.properties` — xem mục **Ký bản release** |
| Tải chậm / lỗi mạng khi build lần đầu | Gradle cần truy cập `dl.google.com`, `repo.maven.apache.org`, `services.gradle.org`; kiểm tra proxy / tường lửa |

---

## Bản quyền

© 2026 TXA. Tên, logo, giao diện và hệ thống tuỳ biến TxTelex thuộc về TXA.

### Thành phần bên thứ ba

Bộ xử lý Telex/VNI trong `app/src/main/java/com/customkey/vietime/engine/` dựa trên
[viettelex](https://github.com/ptrinh/viettelex), giấy phép MIT (Copyright (c) 2026 Phil Trinh) —
toàn văn giấy phép ở `app/src/main/assets/licenses/viettelex-LICENSE.txt`.
