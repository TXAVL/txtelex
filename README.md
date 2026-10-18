# TxTelex — Vietnamese Smart Keyboard

Bàn phím tiếng Việt cho Android: gõ Telex/VNI, gõ vuốt, trackpad phím Cách, xoá theo từ, chạy 100% trên máy.
Package: `nm.txa.telex`.

![TxTelex](docs/assets/playstore_feature_graphic_1024x500.png)

## Build (Windows, không cần Android Studio)

```powershell
.\gradlew.bat testDebugUnitTest   # unit test
.\gradlew.bat assembleDebug       # APK: app\build\outputs\apk\debug\app-debug.apk
```

Yêu cầu: JDK 17, Android SDK (platform 35, build-tools 35.0.0), `local.properties` trỏ `sdk.dir`.

## Bản quyền

© 2026 TXA. Tên, logo, giao diện và hệ thống tuỳ biến TxTelex thuộc về TXA.

### Thành phần bên thứ ba

Bộ xử lý Telex/VNI trong `app/src/main/java/com/customkey/vietime/engine/` dựa trên
[viettelex](https://github.com/ptrinh/viettelex), giấy phép MIT (Copyright (c) 2026 Phil Trinh) —
toàn văn giấy phép ở `app/src/main/assets/licenses/viettelex-LICENSE.txt`.
